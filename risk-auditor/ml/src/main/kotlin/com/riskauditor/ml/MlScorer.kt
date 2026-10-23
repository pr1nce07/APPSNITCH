package com.riskauditor.ml

import com.riskauditor.model.AppSnapshot
import com.riskauditor.model.MlContributor
import com.riskauditor.model.MlLabel
import com.riskauditor.model.MlResult
import kotlin.math.abs
import kotlin.math.exp

/**
 * A trained L2 logistic-regression model, as exported by `ml/training/train.py`.
 *
 * Logistic regression was chosen over a tree ensemble deliberately. Its
 * contribution to a prediction is literally `weight * feature`, so the three
 * features that moved a score can be named honestly. A gradient-boosted model
 * would likely score a point or two higher on the benchmark and would be unable
 * to tell the user *why*, which is the opposite of what PS-01 asks for.
 */
data class ModelSpec(
    /** Permission names, in the order the weights are given. */
    val vocab: List<String>,
    val weights: List<Double>,
    val bias: Double,
    val threshold: Double,
    val datasetName: String,
    val trainedOn: String,
    val modelVersion: String,
    val metrics: Map<String, Double> = emptyMap(),
    /** True when the model was trained on synthetic data and must NOT be presented as meaningful. */
    val synthetic: Boolean = true
) {
    init {
        require(vocab.size == weights.size) {
            "vocab (${vocab.size}) and weights (${weights.size}) must be the same length"
        }
        require(threshold in 0.0..1.0) { "threshold must be within 0.0..1.0" }
    }
}

/**
 * On-device inference (PS-01 stretch goal 13).
 *
 * Two constraints from the problem statement are enforced here rather than left
 * to convention:
 *
 *  - the model never overrides the rule score — this class returns an [MlResult]
 *    and has no access to [com.riskauditor.model.RiskResult] at all;
 *  - the rule reasons remain the source of explanations — [MlResult.topContributors]
 *    is labelled as model input, and the UI shows it only in the technical panel.
 */
class MlScorer(private val spec: ModelSpec) {

    private val index: Map<String, Int> =
        spec.vocab.withIndex().associate { (i, name) -> name to i }

    /**
     * Features are binary "is this permission declared", matching how public
     * permission datasets are built. Granted-vs-declared is not used, because the
     * training data has no such distinction and inventing one at inference time
     * would mean scoring against a different feature space than was trained.
     */
    fun featureVector(snapshot: AppSnapshot): DoubleArray {
        val x = DoubleArray(spec.vocab.size)
        snapshot.permissions.forEach { record ->
            index[record.name]?.let { i -> x[i] = 1.0 }
        }
        return x
    }

    fun score(snapshot: AppSnapshot): MlResult = score(featureVector(snapshot))

    fun score(x: DoubleArray): MlResult {
        require(x.size == spec.vocab.size) {
            "feature vector length ${x.size} does not match vocab ${spec.vocab.size}"
        }

        var z = spec.bias
        for (i in x.indices) {
            if (x[i] != 0.0) z += spec.weights[i] * x[i]
        }
        val probability = sigmoid(z)

        val contributors = x.indices
            .asSequence()
            .filter { x[it] != 0.0 }
            .map { MlContributor(spec.vocab[it], spec.weights[it] * x[it]) }
            .sortedByDescending { abs(it.weight) }
            .take(5)
            .toList()

        return MlResult(
            probability = probability,
            label = if (probability >= spec.threshold) MlLabel.FLAGGED else MlLabel.BENIGN,
            topContributors = contributors,
            modelVersion = spec.modelVersion,
            threshold = spec.threshold
        )
    }

    /**
     * Numerically stable logistic function.
     *
     * The naive `1 / (1 + exp(-z))` overflows for large negative z. Public
     * permission datasets are sparse and high-dimensional, so |z| above 700 is
     * entirely reachable and would otherwise produce NaN on a user's phone.
     */
    private fun sigmoid(z: Double): Double = if (z >= 0) {
        1.0 / (1.0 + exp(-z))
    } else {
        val e = exp(z)
        e / (1.0 + e)
    }

    val modelVersion: String get() = spec.modelVersion
    val isSynthetic: Boolean get() = spec.synthetic
}
