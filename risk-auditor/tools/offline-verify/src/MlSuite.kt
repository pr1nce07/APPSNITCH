package verify

import com.riskauditor.ml.MlScorer
import com.riskauditor.ml.ModelLoader
import com.riskauditor.model.MlLabel
import java.io.File
import kotlin.math.abs

/**
 * Kotlin/Python parity for the on-device model (PS-01 stretch 13).
 *
 * The golden vectors are produced by `ml/training/train.py` from the same fitted
 * model that was exported, so this suite proves the Kotlin port reproduces
 * scikit-learn's own probabilities rather than merely "looking right".
 */
fun mlSuite(goldenPath: String) {
    Check.suite("On-device ML inference (M11)")

    val spec = ModelLoader.loadOrNull()
    if (spec == null) {
        Check.test("model.json is bundled") {
            Check.isTrue(false, "no model at ${ModelLoader.RESOURCE_PATH}; run ml/training/train.py first")
        }
        return
    }

    val scorer = MlScorer(spec)

    Check.test("model metadata is present and self-describing") {
        Check.isTrue(spec.vocab.isNotEmpty(), "empty vocab")
        Check.eq(spec.vocab.size, spec.weights.size, "vocab/weight length")
        Check.isTrue(spec.modelVersion.isNotBlank(), "blank model version")
        Check.isTrue(spec.datasetName.isNotBlank(), "blank dataset name")
    }

    Check.test("every vocab entry is a real permission constant") {
        spec.vocab.forEach {
            Check.isTrue(
                it.startsWith("android.permission."),
                "vocab entry '$it' is not an Android permission constant"
            )
        }
    }

    Check.test("the bundled model is flagged as synthetic (honesty check)") {
        // If this ever fails it means a real model was trained, which is good —
        // update docs/LIMITATIONS.md L-07 at the same time.
        Check.isTrue(
            spec.synthetic,
            "the bundled model claims to be real; confirm the dataset and update LIMITATIONS.md"
        )
    }

    val goldenFile = File(goldenPath)
    if (!goldenFile.exists()) {
        Check.test("golden vectors are present") {
            Check.isTrue(false, "no golden vectors at $goldenPath; run ml/training/train.py")
        }
        return
    }

    val golden = MiniJson.parse(goldenFile.readText()).obj()
    val cases = golden.req("cases").arr()

    Check.test("golden vectors match the bundled model version") {
        Check.eq(golden.req("model_version").str(), spec.modelVersion, "model version")
    }

    Check.test("PARITY: Kotlin reproduces every scikit-learn probability to 1e-6 (${cases.size} cases)") {
        var worst = 0.0
        cases.forEachIndexed { i, node ->
            val c = node.obj()
            val features = c.req("features").arr().map { it.num() }.toDoubleArray()
            val expected = c.req("expected_probability").num()
            val actual = scorer.score(features).probability
            val delta = abs(actual - expected)
            if (delta > worst) worst = delta
            Check.isTrue(
                delta <= 1e-6,
                "case $i: expected $expected, Kotlin produced $actual (delta $delta)"
            )
        }
        println("        max probability delta across ${cases.size} cases: $worst")
    }

    Check.test("label follows the threshold") {
        val zeros = DoubleArray(spec.vocab.size)
        val r = scorer.score(zeros)
        val expected = if (r.probability >= spec.threshold) MlLabel.FLAGGED else MlLabel.BENIGN
        Check.eq(r.label, expected, "label")
    }

    Check.test("sigmoid is numerically stable at extreme inputs") {
        val huge = DoubleArray(spec.vocab.size) { 1e6 }
        val p1 = scorer.score(huge).probability
        val negHuge = DoubleArray(spec.vocab.size) { -1e6 }
        val p2 = scorer.score(negHuge).probability
        Check.isTrue(!p1.isNaN() && p1 in 0.0..1.0, "probability for huge input was $p1")
        Check.isTrue(!p2.isNaN() && p2 in 0.0..1.0, "probability for huge negative input was $p2")
    }

    Check.test("top contributors are the strongest active features, ranked") {
        val x = DoubleArray(spec.vocab.size)
        spec.vocab.indices.take(10).forEach { x[it] = 1.0 }
        val contributors = scorer.score(x).topContributors
        Check.isTrue(contributors.isNotEmpty(), "no contributors reported")
        Check.atMost(contributors.size, 5, "contributor count")
        val magnitudes = contributors.map { abs(it.weight) }
        Check.isTrue(
            magnitudes == magnitudes.sortedDescending(),
            "contributors are not ranked by magnitude: $magnitudes"
        )
    }

    Check.test("an app declaring nothing produces a feature vector of zeros") {
        val s = snapshot(app("com.empty", "Empty"), emptyList())
        Check.isTrue(scorer.featureVector(s).all { it == 0.0 }, "expected an all-zero vector")
    }

    Check.test("declared permissions set their feature regardless of grant state") {
        // The training data has no granted/declared distinction, so inference
        // must not invent one.
        val granted = snapshot(app("com.a", "A"), listOf(perm("android.permission.READ_SMS", granted = true)))
        val declared = snapshot(app("com.b", "B"), listOf(perm("android.permission.READ_SMS", granted = false)))
        Check.isTrue(
            scorer.featureVector(granted).contentEquals(scorer.featureVector(declared)),
            "granted and declared must produce the same feature vector"
        )
    }
}
