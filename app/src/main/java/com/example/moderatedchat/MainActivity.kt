package com.example.moderatedchat

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.moderatedchat.ui.theme.ModeratedChatTheme
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import software.momento.kotlin.sdk.TopicClient
import software.momento.kotlin.sdk.auth.CredentialProvider
import software.momento.kotlin.sdk.config.TopicConfigurations
import software.momento.kotlin.sdk.responses.topic.TopicMessage
import software.momento.kotlin.sdk.responses.topic.TopicSubscribeResponse
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.URL
import java.net.URLEncoder
import java.util.UUID
import javax.net.ssl.HttpsURLConnection
import kotlin.collections.HashMap

const val baseApiUrl = "https://57zovcekn0.execute-api.us-west-2.amazonaws.com/prod"
var momentoApiToken: String = ""
var tokenExpiresAt: Int = 0
// TODO: I really doubt this is right
var topicClient: TopicClient? = null

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ModeratedChatTheme {
                // A surface container using the 'background' color from the theme
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    ModeratedChatApp("pete")
                }
            }
        }
    }
}

data class ChatUser(val name: String, val id: UUID)

data class ChatMessage(
    val timestamp: Long,
    val messageType: String,
    val message: String,
    val sourceLanguage: String,
    val user: ChatUser
)

@Composable
fun ModeratedChatApp(name: String, modifier: Modifier = Modifier) {
    val userId = UUID.randomUUID()
    LaunchedEffect(name) {
        withContext(Dispatchers.IO) {
            coroutineScope {
                launch { getApiToken(name, userId) }
            }
            val credentialProvider = CredentialProvider.fromString(momentoApiToken)
            topicClient = TopicClient(
                credentialProvider = credentialProvider,
                configuration = TopicConfigurations.Laptop.latest
            )
            coroutineScope {
                launch { topicSubscribe(topicClient!!) }
            }
        }
    }
    ModeratedChatLayout(
        userName = name,
        userId = userId,
        modifier = modifier
    )
}

@Composable
fun ModeratedChatLayout(
    userName: String,
    userId: UUID,
    modifier: Modifier = Modifier
) {
    var supportedLanguages by remember { mutableStateOf(mapOf("xx" to "Loading...")) }
    var currentLanguage by remember { mutableStateOf("xx") }
    var currentMessages by remember { mutableStateOf("Waiting for messages...")}
    var chatMessage by remember{ mutableStateOf("") }
    val scope = rememberCoroutineScope()
    Column(
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        LanguageDropdown(
            languages = supportedLanguages,
            onLanguagesLoad = {
                supportedLanguages = it
            },
            language = currentLanguage,
            onLanguageChange = {
                scope.launch {
                    withContext(Dispatchers.IO) {
                        currentLanguage = it
                        println("language changed to $currentLanguage")
                        currentMessages = getMessagesForLanguage(currentLanguage)
                        print("messages changed to $currentMessages")
                    }
                }
            },
            modifier = modifier.fillMaxWidth()
        )
        TextField(
            value = chatMessage,
            onValueChange = { chatMessage = it },
            label = { Text("Type your message . . .") },
            singleLine = true,
            keyboardOptions = KeyboardOptions.Default.copy(
                keyboardType = KeyboardType.Text,
                imeAction = ImeAction.Done
            ),
            modifier = Modifier
                .fillMaxWidth()
        )
        Button(
            onClick = {
                if (chatMessage.isEmpty()) {
                    return@Button
                }
                println("sending message $chatMessage")
                // copy message and language values to send to publish
                val publishMessage = chatMessage
                val publishLanguage = currentLanguage
                scope.launch {
                    publishMessage(
                        userName = userName,
                        userId = userId,
                        currentLanguage = publishLanguage,
                        chatMessage = publishMessage
                    )
                }
                chatMessage = ""
            }
        ) {
            Text(text = "Send")
        }
        MessageList(
            messages = currentMessages,
            modifier = Modifier.fillMaxSize()
        )
    }
}

@Composable
fun MessageList(
    messages: String,
    modifier: Modifier = Modifier
) {
    println("---> MessagesList with messages: $messages")
    var charsToShow = 1000
    if (messages.length < charsToShow) {
        charsToShow = messages.length - 1
    }
    Column(
        modifier = modifier
            .fillMaxSize()
            .wrapContentSize(align = Alignment.Center)
            .background(color = Color.Green)
            .padding(4.dp)
    ) {
        Text(
            // TODO: this causes the app to crash 100% of the time
//            text = messages
            // TODO: however, this works 100% of the time
            text = messages.substring(0 .. charsToShow)
        )
    }
    println("---> Exiting MessagesList")
}

@Composable
fun LanguageDropdown(
    languages: Map<String, String>,
    onLanguagesLoad: (Map<String, String>) -> Unit,
    language: String,
    onLanguageChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    println("---> LanguageDropdown with langs = $languages")
    var menuExpanded by remember { mutableStateOf(false) }
    LaunchedEffect(languages) {
        withContext(Dispatchers.IO) {
            onLanguageChange("en")
            onLanguagesLoad(getSupportedLanguages())
        }
    }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .wrapContentSize(Alignment.TopStart)
            .padding(8.dp)
    ) {
        println("rendering ddl")
        println("langs: $languages")
        Button(onClick = { menuExpanded = !menuExpanded }) {
            Text(text = languages[language] ?: "Loading...")
            Icon(Icons.Default.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(
            expanded = menuExpanded,
            onDismissRequest = {
                menuExpanded = false
                println("menu rollup lang is $language")
            }
        ) {
            for (languageItem in languages.entries.iterator()) {
                DropdownMenuItem(
                    text = { Text(languageItem.value) },
                    onClick = {
                        onLanguageChange(languageItem.key)
                        menuExpanded = false
                    }
                )
            }
        }
    }
    println("---> Exiting LanguageDropdown")
}


suspend fun topicSubscribe(topicClient: TopicClient) {
    // TODO: changing languages should resubscribe
    when (val response = topicClient.subscribe("moderator", "chat-en")) {
        is TopicSubscribeResponse.Subscription -> coroutineScope {
            launch {
                // TODO: how do I do this without a timeout?
                withTimeoutOrNull(5_000_000_000) {
                    response.collect { item ->
                        when (item) {
                            is TopicMessage.Text -> println("Received text message: ${item.value}")
                            is TopicMessage.Binary -> println("Received binary message: ${item.value}")
                            is TopicMessage.Error -> throw RuntimeException(
                                "An error occurred reading messages from topic 'test-topic': ${item.errorCode}", item
                            )
                        }
                    }
                }
            }
        }

        is TopicSubscribeResponse.Error -> throw RuntimeException(
            "An error occurred while attempting to subscribe to topic 'test-topic': ${response.errorCode}", response
        )
    }
}

private fun getApiToken(username: String, id: UUID) {
    val apiUrl = "$baseApiUrl/v1/translate/token"
    var reqParams = URLEncoder.encode("username", "UTF-8") + "=" + URLEncoder.encode(username, "UTF-8")
    reqParams += "&" + URLEncoder.encode("id", "UTF-8") + "=" + URLEncoder.encode(id.toString(), "UTF-8")
    val url = URL(apiUrl)

    with (url.openConnection() as HttpsURLConnection) {
        requestMethod = "POST"
        val wr = OutputStreamWriter(outputStream)
        wr.write(reqParams)
        wr.flush()

        BufferedReader(InputStreamReader(inputStream)).use {
            val response = StringBuffer()

            var inputLine = it.readLine()
            while (inputLine != null) {
                response.append(inputLine)
                inputLine = it.readLine()
            }
            val jsonObject = JSONObject(response.toString())
            momentoApiToken = jsonObject.getString("token")
            tokenExpiresAt = jsonObject.getInt("expiresAtEpoch")
        }
    }
}

private fun getSupportedLanguages(): HashMap<String, String> {
    val supportedLanguages = HashMap<String, String>()
    val apiUrl = "$baseApiUrl/v1/translate/languages"
    val json = URL(apiUrl).readText()
    val jsonObject = JSONObject(json)
    val languages = jsonObject.getJSONArray("supportedLanguages")
    for (i in 0..<languages.length()) {
        val language = languages.getJSONObject(i)
        val value = language.getString("value")
        val label = language.getString("label")
        supportedLanguages[value] = label
    }
    return supportedLanguages
}

private fun getMessagesForLanguage(languageCode: String): String {
    println("Getting messages for $languageCode")
    val apiUrl = "$baseApiUrl/v1/translate/latestMessages/$languageCode"
    println(apiUrl)
    return URL(apiUrl).readText()
}

private suspend fun publishMessage(
    userName: String,
    userId: UUID,
    currentLanguage: String,
    chatMessage: String,
) {
    if (topicClient === null) {
        println("Skipping publish because topic client is null")
    }
    println("chat message is $chatMessage")
    val gson = Gson()
    val user = ChatUser(name = userName, id = userId)
    val message = ChatMessage(
        timestamp = System.currentTimeMillis(),
        message = chatMessage,
        messageType = "text",
        sourceLanguage = currentLanguage,
        user = user
    )
    val jsonMessage = gson.toJson(message)
    println("sending json message: $jsonMessage")
    val publishResponse = topicClient!!.publish(
        cacheName = "moderator",
        topicName = "chat-publish",
        value = jsonMessage
    )
    println("publish response is $publishResponse")
}
