package com.stonicx.assistant.coding.agent

import android.webkit.MimeTypeMap
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import kotlin.concurrent.thread

/**
 * Tiny static file server bound to 127.0.0.1 so a project can be opened in the phone's browser exactly like a real site
 * (relative links, fetch, ES modules all work). Only serves files inside the project folder.
 */
object SiteServer {
    @Volatile private var server: ServerSocket? = null
    private val roots = java.util.concurrent.ConcurrentHashMap<String, File>()

    /** Registers the project and returns the URL of [entry], starting the server if needed. */
    @Synchronized fun urlFor(slug: String, root: File, entry: String): String? {
        roots[slug] = root
        if (server == null || server!!.isClosed) {
            val s = try { ServerSocket(0, 20, InetAddress.getByName("127.0.0.1")) } catch (e: Exception) { return null }
            server = s
            thread(name = "mayra-site", isDaemon = true) {
                while (!s.isClosed) { try { val c = s.accept(); thread(isDaemon = true) { handle(c) } } catch (e: Exception) { break } }
            }
        }
        return "http://127.0.0.1:${server!!.localPort}/$slug/$entry"
    }

    private fun handle(sock: Socket) {
        sock.use { c ->
            try {
                c.soTimeout = 8000
                val reader = c.getInputStream().bufferedReader()
                val request = reader.readLine() ?: return
                while (true) { val h = reader.readLine() ?: break; if (h.isEmpty()) break }
                val raw = request.split(" ").getOrNull(1)?.substringBefore('?')?.substringBefore('#') ?: "/"
                val path = URLDecoder.decode(raw, "UTF-8").trimStart('/')
                val slug = path.substringBefore('/'); var rel = path.substringAfter('/', "")
                val root = roots[slug]
                val out = c.getOutputStream()
                if (root == null) { respond(out, 404, "text/plain", "not found".toByteArray()); return }
                if (rel.isEmpty() || rel.endsWith("/")) rel += "index.html"
                val f = File(root, rel)
                if (!f.canonicalPath.startsWith(root.canonicalPath + File.separator) || !f.isFile) { respond(out, 404, "text/plain", "not found".toByteArray()); return }
                val mime = when (f.extension.lowercase()) {
                    "html", "htm" -> "text/html; charset=utf-8"; "js", "mjs" -> "application/javascript; charset=utf-8"; "css" -> "text/css; charset=utf-8"
                    "json" -> "application/json; charset=utf-8"; "svg" -> "image/svg+xml"
                    else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(f.extension.lowercase()) ?: "text/plain; charset=utf-8"
                }
                respond(out, 200, mime, f.readBytes())
            } catch (_: Exception) { }
        }
    }

    private fun respond(out: java.io.OutputStream, code: Int, mime: String, body: ByteArray) {
        val head = "HTTP/1.1 $code ${if (code == 200) "OK" else "Not Found"}\r\nContent-Type: $mime\r\nContent-Length: ${body.size}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n"
        out.write(head.toByteArray()); out.write(body); out.flush()
    }
}
