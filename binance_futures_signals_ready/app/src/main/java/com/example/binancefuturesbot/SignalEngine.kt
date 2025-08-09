package com.example.binancefuturesbot

import android.content.Context
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.*
import java.io.File
import java.lang.Exception
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.round

data class Kline(
    val openTime: Long,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val closeTime: Long,
    val volume: Double
)

data class Signal(val symbol: String, val side: String, val price: Double, val reason: String, val time: Long)

class SignalEngine private constructor(private val ctx: Context) {
    private val gson = Gson()
    private val client = OkHttpClient.Builder().pingInterval(20, TimeUnit.SECONDS).build()
    private var ws: WebSocket? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val candleBuffer = ArrayDeque<Kline>()
    private val _signals = MutableStateFlow<List<Signal>>(emptyList())
    val signals: StateFlow<List<Signal>> = _signals
    private val _history = MutableStateFlow<List<Signal>>(loadHistoryFromDisk())
    val history: StateFlow<List<Signal>> = _history

    var isRunning = false
        private set

    private val maxBuffer = 1000
    private val historyFileName = "signals_history.json"
    private val channelId = "signals_channel"

    companion object {
        private var INSTANCE: SignalEngine? = null
        fun getInstance(ctx: Context): SignalEngine {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: SignalEngine(ctx.applicationContext).also { INSTANCE = it }
            }
        }
    }

    fun start(symbol: String = "btcusdt", interval: String = "1m") {
        if (isRunning) return
        isRunning = true
        connectWebSocket(symbol, interval)
    }

    fun stop() {
        isRunning = false
        ws?.close(1000, "stopped")
        ws = null
        scope.coroutineContext.cancelChildren()
    }

    private fun connectWebSocket(symbol: String, interval: String) {
        val url = "wss://fstream.binance.com/ws/${'$'}{symbol}@kline_${'$'}{interval}"
        val req = Request.Builder().url(url).build()
        ws = client.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i("SignalEngine", "WS open")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleMessage(text, symbol.uppercase())
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e("SignalEngine", "WS fail: ${'$'}{t.localizedMessage}")
                if (isRunning) {
                    scope.launch {
                        delay(3000)
                        connectWebSocket(symbol, interval)
                    }
                }
            }
        })
    }

    private fun handleMessage(text: String, displaySymbol: String) {
        try {
            val root = gson.fromJson(text, Map::class.java) as Map<*, *>
            val k = root["k"] as? Map<*, *> ?: return
            val isFinal = k["x"] as? Boolean ?: false
            val openTime = (k["t"] as? Number)?.toLong() ?: return
            val open = (k["o"] as? String)?.toDouble() ?: return
            val high = (k["h"] as? String)?.toDouble() ?: return
            val low = (k["l"] as? String)?.toDouble() ?: return
            val close = (k["c"] as? String)?.toDouble() ?: return
            val closeTime = (k["T"] as? Number)?.toLong() ?: return
            val volume = (k["v"] as? String)?.toDouble() ?: return

            val candle = Kline(openTime, open, high, low, close, closeTime, volume)
            if (isFinal) {
                pushCandle(candle)
                evaluateSignals(displaySymbol)
            }
        } catch (e: Exception) {
            Log.e("SignalEngine", "parse error: ${'$'}{e.localizedMessage}")
        }
    }

    private fun pushCandle(k: Kline) {
        candleBuffer.addLast(k)
        while (candleBuffer.size > maxBuffer) candleBuffer.removeFirst()
    }

    private fun evaluateSignals(displaySymbol: String) {
        val candles = candleBuffer.toList()
        if (candles.size < 50) return

        val closes = candles.map { it.close }

        // Indicators
        val ema12 = ema(closes, 12)
        val ema26 = ema(closes, 26)
        val macd = macdLine(closes, 12, 26, 9)
        val macdLine = macd.firstOrNull() ?: return
        val macdSignal = macd.secondOrNull() ?: return
        val rsiVal = rsi(closes, 14).lastOrNull() ?: return

        val latestPrice = closes.last()

        // Simple combined rule:
        // BUY when: EMA12 > EMA26 (uptrend) AND MACD crosses above signal line AND RSI < 75 (not overbought)
        // SELL when: EMA12 < EMA26 AND MACD crosses below signal line AND RSI > 25 (not oversold)
        val prevMacd = macdLine.getOrNull(macdLine.size - 2) ?: return
        val prevSignal = macdSignal.getOrNull(macdSignal.size - 2) ?: return
        val currMacd = macdLine.last()
        val currSignal = macdSignal.last()

        val emaCurr12 = ema12.lastOrNull() ?: return
        val emaCurr26 = ema26.lastOrNull() ?: return
        val emaPrev12 = ema12.getOrNull(ema12.size - 2) ?: return
        val emaPrev26 = ema26.getOrNull(ema26.size - 2) ?: return

        // detect macd crossover
        val macdCrossUp = prevMacd < prevSignal && currMacd > currSignal
        val macdCrossDown = prevMacd > prevSignal && currMacd < currSignal

        val buyCond = emaCurr12 > emaCurr26 && macdCrossUp && rsiVal < 75.0
        val sellCond = emaCurr12 < emaCurr26 && macdCrossDown && rsiVal > 25.0

        if (buyCond) {
            emitSignal(displaySymbol, "BUY", latestPrice, "EMA up + MACD cross up; RSI=${'$'}{round(rsiVal*100)/100}")
        } else if (sellCond) {
            emitSignal(displaySymbol, "SELL", latestPrice, "EMA down + MACD cross down; RSI=${'$'}{round(rsiVal*100)/100}")
        }
    }

    private fun emitSignal(symbol: String, side: String, price: Double, reason: String) {
        val s = Signal(symbol, side, price, reason, System.currentTimeMillis())
        // push to state flow (keep last 200)
        val list = (_signals.value + s).takeLast(200)
        _signals.value = list
        // add to history and persist
        val h = (_history.value + s).takeLast(1000)
        _history.value = h
        saveHistoryToDisk(h)
        // notify user with sound & vibration
        notifyLocal(s)
    }

    private fun notifyLocal(signal: Signal) {
        try {
            val notification = NotificationCompat.Builder(ctx, channelId)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("${'$'}{signal.symbol} ${'$'}{signal.side}")
                .setContentText("${'$'}{signal.reason} @ ${'$'}{signal.price}")
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setDefaults(android.app.Notification.DEFAULT_ALL)
                .build()
            NotificationManagerCompat.from(ctx).notify(signal.time.toInt() and 0x7fffffff, notification)
        } catch (e: Exception) {
            Log.e("SignalEngine", "notify error ${'$'}{e.localizedMessage}")
        }
    }

    // ----- Persistence -----
    private fun historyFile(): File = File(ctx.filesDir, historyFileName)

    private fun saveHistoryToDisk(list: List<Signal>) {
        try {
            val json = gson.toJson(list)
            historyFile().writeText(json)
        } catch (e: Exception) {
            Log.e("SignalEngine", "save history error: ${'$'}{e.localizedMessage}")
        }
    }

    private fun loadHistoryFromDisk(): List<Signal> {
        return try {
            val f = historyFile()
            if (!f.exists()) return emptyList()
            val txt = f.readText()
            val type = object : TypeToken<List<Signal>>() {}.type
            gson.fromJson<List<Signal>>(txt, type) ?: emptyList()
        } catch (e: Exception) {
            Log.e("SignalEngine", "load history error: ${'$'}{e.localizedMessage}")
            emptyList()
        }
    }

    // ----- Indicators -----
    private fun ema(values: List<Double>, period: Int): List<Double> {
        if (values.size < period) return emptyList()
        val out = mutableListOf<Double>()
        val k = 2.0 / (period + 1)
        var prev = values.take(period).average()
        out.add(prev)
        for (i in period until values.size) {
            val v = values[i] * k + prev * (1 - k)
            out.add(v)
            prev = v
        }
        return out
    }

    // returns Pair(macdLineList, signalLineList)
    private fun macdLine(values: List<Double>, short: Int, long: Int, signalPeriod: Int): Pair<List<Double>, List<Double>> {
        if (values.size < long + signalPeriod) return Pair(emptyList(), emptyList())
        val emaShort = ema(values, short)
        val emaLong = ema(values, long)
        // align lengths: emaShort and emaLong have different offsets; use last min length
        val min = minOf(emaShort.size, emaLong.size)
        val macd = mutableListOf<Double>()
        for (i in 0 until min) {
            macd.add(emaShort[emaShort.size - min + i] - emaLong[emaLong.size - min + i])
        }
        val signal = ema(macd, signalPeriod)
        return Pair(macd, signal)
    }

    private fun rsi(values: List<Double>, period: Int): List<Double> {
        if (values.size < period + 1) return emptyList()
        val rsis = mutableListOf<Double>()
        var gain = 0.0
        var loss = 0.0
        for (i in 1..period) {
            val diff = values[i] - values[i - 1]
            if (diff > 0) gain += diff else loss -= diff
        }
        var avgGain = gain / period
        var avgLoss = loss / period
        rsis.add(100 - 100 / (1 + (if (avgLoss == 0.0) 1e9 else avgGain / avgLoss)))
        for (i in period + 1 until values.size) {
            val diff = values[i] - values[i - 1]
            val g = if (diff > 0) diff else 0.0
            val l = if (diff < 0) -diff else 0.0
            avgGain = (avgGain * (period - 1) + g) / period
            avgLoss = (avgLoss * (period - 1) + l) / period
            val rs = if (avgLoss == 0.0) 1e9 else avgGain / avgLoss
            rsis.add(100 - 100 / (1 + rs))
        }
        return rsis
    }
}
