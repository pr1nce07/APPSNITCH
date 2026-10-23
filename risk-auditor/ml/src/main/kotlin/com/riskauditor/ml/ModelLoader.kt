package com.riskauditor.ml

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
internal data class ModelDto(
    @SerialName("model_version") val modelVersion: String,
    @SerialName("dataset_name") val datasetName: String,
    @SerialName("trained_on") val trainedOn: String,
    @SerialName("synthetic") val synthetic: Boolean = true,
    @SerialName("threshold") val threshold: Double,
    @SerialName("bias") val bias: Double,
    @SerialName("vocab") val vocab: List<String>,
    @SerialName("weights") val weights: List<Double>,
    @SerialName("metrics") val metrics: Map<String, Double> = emptyMap()
)

object ModelLoader {

    const val RESOURCE_PATH = "/ml/model.json"

    private val json = Json { ignoreUnknownKeys = true }

    fun fromJson(text: String): ModelSpec {
        val dto = json.decodeFromString<ModelDto>(text)
        return ModelSpec(
            vocab = dto.vocab,
            weights = dto.weights,
            bias = dto.bias,
            threshold = dto.threshold,
            datasetName = dto.datasetName,
            trainedOn = dto.trainedOn,
            modelVersion = dto.modelVersion,
            metrics = dto.metrics,
            synthetic = dto.synthetic
        )
    }

    /** Null when no model has been bundled; the app then simply shows no ML column. */
    fun loadOrNull(): ModelSpec? = try {
        ModelLoader::class.java.getResourceAsStream(RESOURCE_PATH)
            ?.bufferedReader()?.use { fromJson(it.readText()) }
    } catch (t: Throwable) {
        null
    }
}
