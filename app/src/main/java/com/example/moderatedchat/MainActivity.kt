package com.example.moderatedchat

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.moderatedchat.ui.theme.ModeratedChatTheme
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
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
var tokenExpiresAt: Long = 0
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
                    ModeratedChatApp()
                }
            }
        }
    }
}

data class ChatUser(
    val name: String,
    val id: String
)

data class ChatMessage(
    val timestamp: Long,
    val messageType: String,
    val message: String,
    val sourceLanguage: String,
    val user: ChatUser
)

@Composable
fun ModeratedChatApp(modifier: Modifier = Modifier) {
    var userName by remember { mutableStateOf("") }
    val userId = UUID.randomUUID()
    if (userName.isBlank()) {
        ModeratedChatLogin(
            {
                userName = it
            }
        )
    } else {
        ModeratedChatLayout(
            userName = userName,
            userId = userId,
            modifier = modifier
        )
    }
}

@Composable
fun ModeratedChatLogin(
    onLogin: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        var userNameField by remember { mutableStateOf("") }
        Text("Welcome to the Momento Moderated Chat!")
        TextField(
            value = userNameField,
            label = { Text("Choose your username...") },
            singleLine = true,
            onValueChange = {
                userNameField = it
            }
        )
        Button(
            modifier = Modifier.padding(vertical = 24.dp),
            onClick = { onLogin(userNameField) }
        ) {
            Text("Continue")
        }
    }
}

@Composable
fun ModeratedChatLayout(
    userName: String,
    userId: UUID,
    modifier: Modifier = Modifier
) {
    var supportedLanguages by remember { mutableStateOf(mapOf("xx" to "Loading...")) }
    var currentLanguage by remember { mutableStateOf("xx") }
    val currentMessages = remember { mutableStateListOf<ChatMessage>() }
    var chatMessage by remember{ mutableStateOf("") }
    var subscribeJob by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()
    Column(
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        LanguageDropdown(
            languages = supportedLanguages,
            onLanguagesLoad = {
                supportedLanguages = it
            },
            language = currentLanguage,
            onLanguageChange = {
                println("onLanguage change: $currentLanguage -> $it")
                // TODO: not sure how much of this is necessary, but the withContext def is
                scope.launch {
                    withContext(Dispatchers.IO) {
                        coroutineScope {
                            launch {
                                // TODO: Ok, so how do I reliably resubscribe after my token expires?
                                val tokenExpiresInSecs = tokenExpiresAt - (System.currentTimeMillis() / 1000)
                                println("token expires in $tokenExpiresInSecs")
                                if (topicClient == null || tokenExpiresInSecs < 10) {
                                    topicClient?.close()
                                    getTopicClient(userName, userId)
                                }
                            }
                        }
                        if (currentLanguage == it) {
                            println("language $currentLanguage not changed. skipping.")
                            return@withContext
                        }
                        currentLanguage = it
                        println("language changed to $currentLanguage")
                        currentMessages.clear()
                        getMessagesForLanguage(languageCode = currentLanguage) {
                            for (i in 0..<it.count()) {
                                currentMessages.add(it[i])
                            }
                        }
                        println("messages refreshed")
                        if (subscribeJob != null) {
                            println("cancelling existing subscribe job")
                            subscribeJob!!.cancelAndJoin()
                        }
                        subscribeJob = launch {
                            topicSubscribe(language = currentLanguage)
                            {
                                val jsonMessage = JSONObject(it)
                                val parsedMessage = parseMessage(jsonMessage)
                                currentMessages.add(parsedMessage)
                                println("message added to current messages list")
                            }
                        }
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
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
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
    messages: List<ChatMessage>,
    modifier: Modifier = Modifier
) {
    LazyColumn(modifier = modifier.padding(4.dp)) {
        items(items = messages) { item ->
            ChatEntry(
                message = item
            )
        }
    }
}

@Composable
fun ChatEntry(message: ChatMessage, modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(vertical = 4.dp, horizontal = 8.dp)
    ) {
        Column(modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)) {
            Text(
                text = message.user.name,
                modifier = modifier
            )
            Text(
                text = message.message,
                modifier = modifier
            )
        }
    }
}

@Composable
fun LanguageDropdown(
    languages: Map<String, String>,
    onLanguagesLoad: (Map<String, String>) -> Unit,
    language: String,
    onLanguageChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var menuExpanded by remember { mutableStateOf(false) }
    LaunchedEffect(languages) {
        withContext(Dispatchers.IO) {
            onLanguagesLoad(getSupportedLanguages())
            onLanguageChange("en")
        }
    }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .wrapContentSize(Alignment.TopStart)
            .padding(8.dp)
    ) {
        Button(onClick = { menuExpanded = !menuExpanded }) {
            Text(text = languages[language] ?: "Loading...")
            Icon(Icons.Default.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(
            expanded = menuExpanded,
            onDismissRequest = {
                menuExpanded = false
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
}


suspend fun topicSubscribe(
    language: String,
    onMessage: (String) -> Unit
) {
    println("Subscribing to chat-$language")
    when (val response = topicClient!!.subscribe("moderator", "chat-$language")) {
        is TopicSubscribeResponse.Subscription -> coroutineScope {
            val subscribeBeginSecs = System.currentTimeMillis() / 1000
            launch {
                response.collect { item ->
                    yield()
                    println("subscribed for ${(System.currentTimeMillis() / 1000) - subscribeBeginSecs} seconds")
                    when (item) {
                        is TopicMessage.Text -> {
                            println("Received text message: ${item.value}")
                            onMessage(item.value)
                        }
                        is TopicMessage.Binary -> {
                            println("Received binary message: ${item.value}")
                            onMessage("${item.value}")
                        }
                        is TopicMessage.Error -> throw RuntimeException(
                            "An error occurred reading messages from topic 'test-topic': ${item.errorCode}", item
                        )
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
            tokenExpiresAt = jsonObject.getLong("expiresAtEpoch")
            println("api token expires in ${tokenExpiresAt - (System.currentTimeMillis() / 1000)} secs")
        }
    }
}

private fun getTopicClient(
    userName: String,
    userId: UUID
) {
    // TODO: move api token to remember and pass in callback
    getApiToken(userName, userId)
    val credentialProvider =
        CredentialProvider.fromString(momentoApiToken)
    topicClient = TopicClient(
        credentialProvider = credentialProvider,
        configuration = TopicConfigurations.Laptop.latest
    )
    println("got new topic client $topicClient")
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

private fun getMessagesForLanguage(
    languageCode: String,
    onMessages: (List<ChatMessage>) -> Unit
) {
    println("Getting messages for $languageCode")
    val apiUrl = "$baseApiUrl/v1/translate/latestMessages/$languageCode"
    val messages = URL(apiUrl).readText()
    println("received ${messages.length} bytes")
    val jsonObject = JSONObject(messages)
    val messagesFromJson = jsonObject.getJSONArray("messages")
    val messageList = mutableListOf<ChatMessage>()
    for (i in 0..<messagesFromJson.length()) {
        val message =  messagesFromJson.getJSONObject(i)
        // TODO: image support
        if (message.getString("messageType") != "text") {
            continue
        }
        val parsedMessage = parseMessage(message = message)
        messageList.add(parsedMessage)
    }
    onMessages(messageList.toList())
}

private fun parseMessage(message: JSONObject): ChatMessage {
    val messageType = message.getString("messageType")
    val messageText = message.getString("message")
    val timestamp = message.getLong("timestamp")
    val sourceLanguage = message.getString("sourceLanguage")
    val authorJson = message.getJSONObject("user")
    val name = authorJson.getString("username")
    val id = authorJson.getString("id")
    return ChatMessage(
        user = ChatUser(
            name = name, id = id
        ),
        messageType = messageType,
        message = messageText,
        sourceLanguage = sourceLanguage,
        timestamp = timestamp
    )
}

private suspend fun publishMessage(
    userName: String,
    userId: UUID,
    currentLanguage: String,
    chatMessage: String,
) {
    val tokenExpiresInSecs = tokenExpiresAt - (System.currentTimeMillis() / 1000)
    if (tokenExpiresInSecs < 10) {
        withContext(Dispatchers.IO) {
            topicClient?.close()
            getTopicClient(userName, userId)
        }
    }
    val gson = Gson()
    val user = ChatUser(name = userName, id = userId.toString())
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
