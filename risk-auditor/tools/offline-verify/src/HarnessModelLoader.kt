/* Offline replacement for ModelLoader. See HarnessLoaders.kt for why this exists. */

package com.riskauditor.ml

import verify.MiniJson
import verify.arr
import verify.bool
import verify.num
import verify.obj
import verify.opt
import verify.req
import verify.str

object ModelLoader {

    const val RESOURCE_PATH = "/ml/model.json"

    fun fromJson(text: String): ModelSpec {
        val root = MiniJson.parse(text).obj()
        return ModelSpec(
            vocab = root.req("vocab").arr().map { it.str() },
            weights = root.req("weights").arr().map { it.num() },
            bias = root.req("bias").num(),
            threshold = root.req("threshold").num(),
            datasetName = root.req("dataset_name").str(),
            trainedOn = root.req("trained_on").str(),
            modelVersion = root.req("model_version").str(),
            metrics = (root.opt("metrics")?.obj() ?: emptyMap()).mapValues { it.value.num() },
            synthetic = root.opt("synthetic")?.bool() ?: true
        )
    }

    fun loadOrNull(): ModelSpec? = try {
        ModelLoader::class.java.getResourceAsStream(RESOURCE_PATH)
            ?.bufferedReader()?.use { fromJson(it.readText()) }
    } catch (t: Throwable) {
        null
    }
}
