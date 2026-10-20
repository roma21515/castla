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
            val builder = Builder()
                .setSession("CastlaVirtualIp")
                .addAddress(TeslaVirtualIp.VIRTUAL_IP, TeslaVirtualIp.VIRTUAL_PREFIX)
                .addRoute(TeslaVirtualIp.VIRTUAL_IP, TeslaVirtualIp.VIRTUAL_PREFIX)
                .setMtu(1500)
                .setBlocking(true)
            // Keep Castla's own sockets (HTTP server replies) out of the VPN routing,
            // so replies to hotspot clients go out via the hotspot interface.
            try {
                builder.addDisallowedApplication(packageName)
            } catch (e: Exception) {
                android.util.Log.w("TeslaVpnService", "addDisallowedApplication failed", e)
            }
            vpnInterface = builder.establish()
            if (vpnInterface == null) {
                android.util.Log.e("TeslaVpnService", "establish() returned null (VPN permission revoked?)")
                stopSelf()
                return START_NOT_STICKY
            }
            android.util.Log.i("TeslaVpnService", "VPN established, ${TeslaVirtualIp.VIRTUAL_IP}/${TeslaVirtualIp.VIRTUAL_PREFIX}")

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
