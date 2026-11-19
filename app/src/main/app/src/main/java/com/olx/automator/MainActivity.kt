package com.example.olxautosender

import android.annotation.SuppressLint
import android.content.Context
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import android.view.MotionEvent
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.*
import org.json.JSONArray
import java.net.URLEncoder
import kotlin.coroutines.resume
import kotlin.random.Random

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var etSearchQuery: EditText
    private lateinit var etMessageText: EditText
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var tvStatus: TextView

    private var isRunning = false
    private var botJob: Job? = null
    private val mainScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var wakeLock: PowerManager.WakeLock? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        etSearchQuery = findViewById(R.id.etSearchQuery)
        etMessageText = findViewById(R.id.etMessageText)
        btnStart = findViewById(R.id.btnStart)
        btnStop = findViewById(R.id.btnStop)
        tvStatus = findViewById(R.id.tvStatus)
        webView = findViewById(R.id.webView)

        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(webView, true)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            userAgentString = "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Mobile Safari/537.36"
        }

        webView.webViewClient = WebViewClient()
        webView.loadUrl("https://www.olx.kz/myaccount/")

        btnStart.setOnClickListener { startBot() }
        btnStop.setOnClickListener { stopBot() }
    }

    private fun acquireWakeLock() {
        if (wakeLock == null) {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "OlxAutoSender::WakelockTag"
            )
        }
        if (wakeLock?.isHeld == false) {
            wakeLock?.acquire(10 * 60 * 60 * 1000L) // до 10 часов непрерывной работы в фоне
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (_: Exception) {}
    }

    private fun startBot() {
        val query = etSearchQuery.text.toString().trim()
        val message = etMessageText.text.toString().trim()

        if (query.isEmpty() || message.isEmpty()) {
            updateStatus("Ошибка: заполните запрос и текст!")
            return
        }

        isRunning = true
        btnStart.isEnabled = false
        btnStop.isEnabled = true
        acquireWakeLock()

        botJob = mainScope.launch {
            var currentPage = 1
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            // Разбиваем поисковый запрос на ключевые слова для фильтрации (например: "бухгалтер", "удаленно" и т.д.)
            val queryKeywords = query.lowercase().split(" ").filter { it.length > 2 }

            while (isRunning) {
                val searchUrl = "https://www.olx.kz/list/q-$encodedQuery/?page=$currentPage"
                updateStatus("Стр. $currentPage: загрузка списка...")
                webView.loadUrl(searchUrl)
                delay(5500)

                evalJs("window.scrollTo({top: document.body.scrollHeight / 2, behavior: 'smooth'});")
                delay(1500)
                evalJs("window.scrollTo({top: document.body.scrollHeight, behavior: 'smooth'});")
                delay(2000)

                val linksJson = evalJs("""
                    (function() {
                        const anchors = Array.from(document.querySelectorAll('a[href*="/d/obyavlenie/"]'));
                        const urls = anchors.map(a => a.href.split('#')[0].split('?')[0]);
                        return JSON.stringify([...new Set(urls)]);
                    })();
                """.trimIndent())

                val links = parseJsonArray(linksJson)
                if (links.isEmpty()) {
                    updateStatus("Объявления закончились. Стоп.")
                    stopBot()
                    break
                }

                for ((index, adUrl) in links.withIndex()) {
                    if (!isRunning) break

                    if (isAlreadyContacted(adUrl)) {
                        updateStatus("Пропуск дубликата (${index + 1}/${links.size})")
                        delay(200)
                        continue
                    }

                    updateStatus("Анкета ${index + 1}/${links.size}: проверка...")
                    webView.loadUrl(adUrl)
                    delay(4500)

                    // СТРОГАЯ ФИЛЬТРАЦИЯ ПО ТЕМАТИКЕ: проверяем заголовок или текст объявления на соответствие запросу
                    val adTitle = evalJs("""
                        (function() {
                            const h1 = document.querySelector('h1');
                            return h1 ? h1.innerText.toLowerCase() : "";
                        })();
                    """.trimIndent()).replace("\"", "")

                    if (queryKeywords.isNotEmpty() && adTitle.isNotEmpty()) {
                        val matches = queryKeywords.any { adTitle.contains(it) }
                        if (!matches) {
                            updateStatus("Пропуск (не по теме запроса): '$adTitle'")
                            markAsContacted(adUrl) // запоминаем, чтобы не проверять повторно
                            delay(400)
                            continue
                        }
                    }

                    simulateSmallSwipeDown()
                    evalJs("window.scrollBy({top: 250, behavior: 'smooth'});")
                    delay(1200)

                    val sellerId = evalJs("""
                        (function() {
                            const el = document.querySelector('[data-testid="user-profile-link"], h4');
                            return el ? el.innerText.trim() : "";
                        })();
                    """.trimIndent()).replace("\"", "")

                    if (sellerId.isNotEmpty() && isAlreadyContacted("SELLER_$sellerId")) {
                        updateStatus("Пропуск: компании '$sellerId' уже писали.")
                        markAsContacted(adUrl)
                        delay(500)
                        continue
                    }

                    // Нажимаем кнопку "Сообщение"
                    val clickRes = evalJs("""
                        (function() {
                            const btns = Array.from(document.querySelectorAll('button, a, [role="button"]'));
                            const msgBtn = btns.find(b => {
                                const txt = (b.innerText || b.getAttribute('aria-label') || '').toLowerCase();
                                const testId = (b.getAttribute('data-testid') || b.getAttribute('data-cy') || '').toLowerCase();
                                return txt.includes('сообщение') || 
                                       txt.includes('написать') || 
                                       testId.includes('chat') || 
                                       testId.includes('message');
                            });
                            if (msgBtn) {
                                msgBtn.click();
                                return "CLICKED";
                            }
                            return "NOT_FOUND";
                        })();
                    """.trimIndent())

                    val clickedMsgBtn = clickRes.contains("CLICKED")
                    delay(3500) // Ждем загрузки чата

                    // ПРОВЕРКА ИСТОРИИ ЧАТА: если там уже есть сообщения или диалог не пустой — пропускаем!
                    val isChatAlreadyUsed = evalJs("""
                        (function() {
                            const chatContainer = document.querySelector('[data-testid="chat-messages"], .css-1owrcbn, div[class*="message"]');
                            const messages = document.querySelectorAll('div[class*="message"], p[class*="text"]');
                            // Если поле ввода уже содержит текст или в чате есть предыдущие сообщения
                            const textarea = document.querySelector('textarea[name="message"], textarea');
                            if (textarea && textarea.value.trim().length > 0) return "YES";
                            if (messages.length > 3) return "YES"; // Если есть старая переписка
                            return "NO";
                        })();
                    """.trimIndent())

                    if (isChatAlreadyUsed.contains("YES")) {
                        updateStatus("Пропуск: диалог с этим клиентом уже ведется!")
                        markAsContacted(adUrl)
                        if (sellerId.isNotEmpty()) markAsContacted("SELLER_$sellerId")
                        delay(1000)
                        continue
                    }

                    val safeMsg = message.replace("\"", "\\\"").replace("\n", "\\n")
                    val sendResult = evalJs("""
                        (function() {
                            const textarea = document.querySelector('textarea[name="message"], textarea, input[type="text"][placeholder*="сообщ" i]');
                            if (!textarea) return "NO_CHAT";

                            textarea.focus();

                            const proto = textarea.tagName.toLowerCase() === 'textarea' 
                                ? window.HTMLTextAreaElement.prototype 
                                : window.HTMLInputElement.prototype;
                            const nativeSetter = Object.getOwnPropertyDescriptor(proto, "value").set;
                            nativeSetter.call(textarea, "$safeMsg");
                            textarea.dispatchEvent(new Event('input', { bubbles: true }));
                            textarea.dispatchEvent(new Event('change', { bubbles: true }));

                            const submitBtn = document.querySelector('button[type="submit"], [data-testid*="send" i]') || 
                                Array.from(document.querySelectorAll('button')).find(b => 
                                    (b.innerText || '').toLowerCase().includes('отправить')
                                );

                            if (submitBtn && !submitBtn.disabled) {
                                submitBtn.click();
                                return "SENT";
                            }
                            return "NO_BUTTON";
                        })();
                    """.trimIndent())

                    if (sendResult.contains("SENT")) {
                        markAsContacted(adUrl)
                        if (sellerId.isNotEmpty()) markAsContacted("SELLER_$sellerId")

                        // Рандомная пауза от 5 до 10 секунд
                        val waitSec = Random.nextInt(5, 11)
                        for (sec in waitSec downTo 1) {
                            if (!isRunning) break
                            updateStatus("Отправлено! Пауза: $sec сек...")
                            delay(1000)
                        }
                    } else {
                        updateStatus(if (clickedMsgBtn) "Не удалось отправить, дальше..." else "Сообщения закрыты, дальше...")
                        markAsContacted(adUrl)
                        delay(1500)
                    }
                }
                currentPage++
            }
        }
    }

    private suspend fun simulateSmallSwipeDown() {
        val downTime = SystemClock.uptimeMillis()
        val x = if (webView.width > 0) webView.width / 2f else 500f
        val startY = if (webView.height > 0) webView.height * 0.65f else 900f
        val endY = if (webView.height > 0) webView.height * 0.45f else 650f

        val downEvent = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, x, startY, 0)
        webView.dispatchTouchEvent(downEvent)
        downEvent.recycle()

        val steps = 5
        for (i in 1..steps) {
            delay(15)
            val now = SystemClock.uptimeMillis()
            val currentY = startY - ((startY - endY) * i / steps)
            val moveEvent = MotionEvent.obtain(downTime, now, MotionEvent.ACTION_MOVE, x, currentY, 0)
            webView.dispatchTouchEvent(moveEvent)
            moveEvent.recycle()
        }

        delay(15)
        val upTime = SystemClock.uptimeMillis()
        val upEvent = MotionEvent.obtain(downTime, upTime, MotionEvent.ACTION_UP, x, endY, 0)
        webView.dispatchTouchEvent(upEvent)
        upEvent.recycle()
    }

    private fun stopBot() {
        isRunning = false
        botJob?.cancel()
        releaseWakeLock()
        btnStart.isEnabled = true
        btnStop.isEnabled = false
        updateStatus("Статус: Остановлено")
    }

    private fun updateStatus(text: String) {
        tvStatus.text = text
    }

    private suspend fun evalJs(script: String): String = suspendCancellableCoroutine { cont ->
        webView.evaluateJavascript(script) { result ->
            if (cont.isActive) cont.resume(result ?: "")
        }
    }

    private fun parseJsonArray(rawResult: String): List<String> {
        val list = mutableListOf<String>()
        try {
            val cleaned = rawResult
                .removeSurrounding("\"")
                .replace("\\\"", "\"")
                .replace("\\\\", "\\")
            val jsonArray = JSONArray(cleaned)
            for (i in 0 until jsonArray.length()) {
                list.add(jsonArray.getString(i))
            }
        } catch (_: Exception) {}
        return list
    }

    private fun isAlreadyContacted(key: String): Boolean {
        val prefs = getSharedPreferences("olx_sent_db", Context.MODE_PRIVATE)
        return prefs.getBoolean(key, false)
    }

    private fun markAsContacted(key: String) {
        val prefs = getSharedPreferences("olx_sent_db", Context.MODE_PRIVATE)
        prefs.edit().putBoolean(key, true).apply()
    }

    override fun onDestroy() {
        super.onDestroy()
        releaseWakeLock()
        mainScope.cancel()
    }
}
