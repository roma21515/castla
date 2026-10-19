package com.castla.mirror.network

import android.net.VpnService
import android.content.Intent
import android.os.ParcelFileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer

class TeslaVpnService : VpnService() {

    private var vpnInterface: ParcelFileDescriptor? = null
    private var isRunning = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            // Создаем виртуальный TUN-интерфейс с вашим IP
            vpnInterface = Builder()
                .setSession("CastlaVirtualIp")
                .addAddress(TeslaVirtualIp.VIRTUAL_IP, TeslaVirtualIp.VIRTUAL_PREFIX)
                .addRoute(TeslaVirtualIp.VIRTUAL_IP, TeslaVirtualIp.VIRTUAL_PREFIX)
                .setMtu(1500)
                .establish()

            isRunning = true
            
            // Запуск потока для обработки трафика (если требуется перенаправление на локальный порт)
            startPacketLoop()

        } catch (e: Exception) {
            e.printStackTrace()
            stopSelf()
        }
        return START_STICKY
    }

    private fun startPacketLoop() {
        Thread {
            val descriptor = vpnInterface?.fileDescriptor ?: return@Thread
            val inputStream = FileInputStream(descriptor)
            val outputStream = FileOutputStream(descriptor)
            val buffer = ByteBuffer.allocate(32767)

            try {
                while (isRunning) {
                    val length = inputStream.read(buffer.array())
                    if (length > 0) {
                        // Здесь при желании можно обрабатывать IP-пакеты, 
                        // либо просто держать интерфейс открытым, чтобы IP был доступен системе.
                    }
                }
            } catch (e: Exception) {
                // Ошибка чтения/записи или интерфейс закрыт
            }
        }.start()
    }

    override fun onDestroy() {
        isRunning = false
        vpnInterface?.close()
        vpnInterface = null
        super.onDestroy()
    }
}
