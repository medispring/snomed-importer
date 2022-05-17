package com.icure.snomed.importer

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.core.json.JsonReadFeature
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.KotlinModule
import io.icure.kraken.client.apis.CodeApi
import io.icure.kraken.client.models.CodeDto
import io.icure.kraken.client.models.ListOfIdsDto
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.persistentHashMapOf
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import org.springframework.boot.CommandLineRunner
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import kotlin.io.path.Path
import kotlin.io.path.reader

operator fun List<String>.component3() = this[2]
operator fun List<String>.component4() = this[3]
operator fun List<String>.component5() = this[4]
operator fun List<String>.component6() = this[5]
operator fun List<String>.component7() = this[6]
operator fun List<String>.component8() = this[7]
operator fun List<String>.component9() = this[8]

@SpringBootApplication
@ExperimentalCoroutinesApi
@ExperimentalStdlibApi
class ImporterApplication : CommandLineRunner {
    private val objectMapper: ObjectMapper = ObjectMapper()
        .registerModule(KotlinModule())
        .registerModule(JavaTimeModule()).apply {
            setSerializationInclusion(JsonInclude.Include.NON_NULL)
        }

    override fun run(args: Array<String>) {
        val snomedMappings: Map<String, List<String>> = Path(args[0]).reader(Charsets.UTF_8).readLines()
            .fold(persistentHashMapOf<String, PersistentList<String>>()) { map, it ->
                val (_, _, active, _, _, referencedComponentId, mapTarget) = it.split("\t")
                if (active == "1") {
                    map.put(referencedComponentId, (map[referencedComponentId] ?: persistentListOf()).add(mapTarget))
                } else map
            }
        val acceptabilityMapping =
            Path(args[1]).reader(Charsets.UTF_8).readLines().fold(persistentHashMapOf<String, Int>()) { map, it ->
                val (_, _, active, _, _, referencedComponentId, acceptabilityId) = it.split("\t")
                if (active == "1") {
                    map.put(
                        referencedComponentId, when (acceptabilityId) {
                            "900000000000549004" -> 1
                            "900000000000548007" -> 2
                            else -> 0
                        }
                    )
                } else map
            }
        val terms = Path(args[2]).reader(Charsets.UTF_8).readLines()
            .fold(persistentHashMapOf<String, Map<String, List<String>>>()) { map, it ->
                val (id, _, active, _, conceptId, languageCode, _, term, _) = it.split("\t")
                if (active == "1" && (acceptabilityMapping[id] ?: 0) >= 1) {
                    map.put(conceptId, (map[conceptId] ?: mapOf()).let { lngMap ->
                        lngMap + (languageCode to ((lngMap[languageCode] ?: emptyList()) + term))
                    })
                } else map
            }
        val userName = args[3]
        val password = args[4]

        runBlocking {
            val codeApi =
                CodeApi(basePath = "http://127.0.0.1:16043", authHeader = basicAuth(userName, password))
            val startKey = null
            val startDocumentId = null
            addSnomedToIbui(codeApi, startKey, startDocumentId, snomedMappings, terms)
        }
    }

    private suspend fun addSnomedToIbui(
        codeApi: CodeApi,
        startKey: String?,
        startDocumentId: String?,
        snomedMappings: Map<String, List<String>>,
        terms: Map<String, Map<String, List<String>>>
    ) {
        val ibuis = codeApi.findCodesByType("be", "BE-THESAURUS", null, null, startKey, startDocumentId, 1000)

        val toBeUpdated = ibuis.rows.mapNotNull { code ->
            val toBeAdded = snomedMappings[code.code] ?: emptyList()
            val snomeds = code.qualifiedLinks.entries.filter { (key, value) -> isSnomed(key, value) }
            val missing = toBeAdded.filter { s -> !snomeds.any { (_, v) -> v.contains("|$s|") } }
            if (missing.isNotEmpty()) {
                code.copy(
                    qualifiedLinks = code.qualifiedLinks + ("narrower" to (
                            (code.qualifiedLinks["narrower"]) ?: emptyList()) + missing.map { "SNOMED|$it|20220315" })
                )
            } else null
        }

        val snomed = toBeUpdated.flatMap { it.qualifiedLinks.entries.filter { (key, value) -> isSnomed(key, value) }.flatMap { s -> s.value } }.toSortedSet()
        val existingSnomeds = snomed.toList().takeIf { it.isNotEmpty() }?.let { ids -> codeApi.getCodes(ListOfIdsDto(ids)).map { it.id to it }.toMap() } ?: emptyMap()

        snomed.filter { !existingSnomeds.containsKey(it) }.forEach {
            val codeDto = makeCode(it, terms)
            codeApi.createCode(codeDto)
        }

        snomed.filter { existingSnomeds.containsKey(it) }.forEach {
            val existing = existingSnomeds[it]!!
            val codeDto = makeCode(it, terms)
            codeApi.modifyCode(codeDto.copy(rev = existing.rev))
        }

        toBeUpdated.forEach {
            codeApi.modifyCode(it)
        }

        ibuis.nextKeyPair?.let {
            addSnomedToIbui(codeApi, objectMapper.writeValueAsString(it.startKey), it.startKeyDocId, snomedMappings, terms)
        }
    }

    private fun makeCode(
        id: String,
        terms: Map<String, Map<String, List<String>>>
    ): CodeDto {
        val (type, code, version) = id.split("|")
        val label = terms[code] ?: emptyMap()
        val codeDto = CodeDto(
            id = id,
            type = type,
            code = code,
            version = version,
            label = label.entries.associate { (k, v) -> k to v.first() },
            searchTerms = label.entries.associate { (k, v) -> k to v.toSet() })
        return codeDto
    }

    private fun isSnomed(key: String, value: List<String>) = listOf(
        "exact",
        "narrower",
        "broader",
        "approximate"
    ).contains(key) && value.any { it.startsWith("SNOMED|") }
}

@ExperimentalCoroutinesApi
@ExperimentalStdlibApi
fun main(args: Array<String>) {
    runApplication<ImporterApplication>(*args)
}
