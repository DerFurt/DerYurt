package com.derfurt.deryurt.core

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Kaynak kodda kimlik bilgisi loglanmasını engelleyen bekçi test.
 * Log/println satırlarında kullanıcı adı, şifre veya Credentials geçmemeli;
 * OkHttp logging interceptor hiç kullanılmamalı.
 */
class NoSecretLoggingTest {

    private val sources: List<File> =
        File("src/main/java").walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    @Test
    fun sourcesFound() {
        assertTrue("Kaynak dosyalar bulunamadı (çalışma dizini app/ olmalı)", sources.isNotEmpty())
    }

    @Test
    fun noCredentialInLogCalls() {
        val logCall = Regex("""\b(Log\.[vdiwe]|println|print|printStackTrace)\s*\(""")
        val secret = Regex("""(?i)(password|username|credential|j_password|j_username|sifre|şifre|creds)""")
        val offenders = sources.flatMap { f ->
            f.readLines().mapIndexedNotNull { i, line ->
                if (logCall.containsMatchIn(line) && secret.containsMatchIn(line)) "${f.name}:${i + 1}" else null
            }
        }
        assertTrue("Kimlik bilgisi loglanıyor olabilir: $offenders", offenders.isEmpty())
    }

    @Test
    fun noHttpLoggingInterceptor() {
        val offenders = sources.filter { it.readText().contains("HttpLoggingInterceptor") }.map { it.name }
        assertTrue("HttpLoggingInterceptor kullanılmamalı: $offenders", offenders.isEmpty())
    }
}
