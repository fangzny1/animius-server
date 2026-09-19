package com.lanlinju.animius.di

import com.lanlinju.animius.util.createHttpClient
import io.ktor.client.HttpClient

val DefaultHttpClient: HttpClient by lazy { createHttpClient() }
