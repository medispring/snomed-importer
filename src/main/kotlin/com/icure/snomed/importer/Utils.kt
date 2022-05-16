package com.icure.snomed.importer

fun basicAuth(userName: String, password: String) =
    "Basic ${java.util.Base64.getEncoder().encodeToString("$userName:$password".toByteArray())}"
