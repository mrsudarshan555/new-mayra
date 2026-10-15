package com.stonicx.assistant.tools.creator

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.net.Uri
import android.provider.ContactsContract
import android.provider.MediaStore
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.core.content.FileProvider
import com.stonicx.assistant.tools.*
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.io.File
import java.io.FileOutputStream

/** Content creation: scripts, deep research, images, quote cards, email drafts, reports and social posting. */
object CreatorTools {
    private val PINNED = setOf("generate_image", "deep_research")

    fun all(): List<MayraTool> = listOf(
        writeVideoScript, saveScriptToNotes, deepResearch, generateImage, editPhoto, generateQuoteImage, writeEmail, findEmailAddress,
        sendEmail, sendReport, postPhotoToSocial, postStory
    ).map { if (it.name in PINNED) it.pin() else it }

    private fun launch(ctx: ToolContext, i: Intent): Boolean = try { ctx.context.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true } catch (e: Exception) { false }
    private fun uriFor(ctx: ToolContext, f: File): Uri? = try { FileProvider.getUriForFile(ctx.context, ctx.context.packageName + ".fileprovider", f) } catch (e: Exception) { null }
    private fun docsDir(ctx: ToolContext, name: String) = File(ctx.context.getExternalFilesDir(null) ?: ctx.context.filesDir, name).apply { mkdirs() }
    private fun safeName(s: String) = s.trim().replace(Regex("[^A-Za-z0-9\\u0900-\\u097F _-]"), "").replace(' ', '_').take(40).ifEmpty { "mayra" }

    /** A photo path, or "latest" for the newest picture in the gallery. */
    private fun resolvePhoto(ctx: ToolContext, path: String): Pair<File?, String?> {
        val p = path.trim()
        if (p.isNotEmpty() && !p.equals("latest", true)) { val f = File(p); return if (f.isFile) f to null else null to "Photo nahi mili: $p" }
        return try {
            ctx.context.contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.Images.Media.DATA), null, null, "${MediaStore.Images.Media.DATE_ADDED} DESC")?.use { c ->
                if (c.moveToFirst()) File(c.getString(0)).takeIf { it.isFile } to null else null to "Gallery me koi photo nahi mili."
            } ?: (null to "Gallery nahi padh paayi.")
        } catch (e: SecurityException) { null to "Photos ki permission nahi hai - App permissions me allow karo, ya photo ka path batao." }
    }

    private val writeVideoScript = tool("write_video_script", "Write a ready-to-record script for a video/reel/short (hook, body, call-to-action) in the user's language.",
        p("topic", "What the video is about", true), p("minutes", "Length in minutes, default 1", type = "NUMBER"), p("style", "e.g. funny, educational, storytelling, news"), p("language", "Hindi, Hinglish, English ... default Hinglish")) { ctx, a ->
        val ans = SubAgent.ask(ctx.app, "You are a scriptwriter for short-form and YouTube videos. Write a script with: HOOK (first 3 seconds), then the BODY in short spoken lines, then a CALL TO ACTION. Mark pauses/visual cues in [brackets]. Keep it natural to speak aloud, no filler intro.",
            "Topic: ${a.optString("topic")}\nLength: about ${a.optDouble("minutes", 1.0)} minute(s)\nStyle: ${a.optString("style").ifBlank { "engaging" }}\nLanguage: ${a.optString("language").ifBlank { "Hinglish" }}", temperature = 0.8)
        if (ans.text != null) ToolResult.ok(ans.text.trim()) else ToolResult.fail(ans.error ?: "Script nahi ban paayi.")
    }

    private val saveScriptToNotes = tool("save_script_to_notes", "Save text (a script, notes, a list) as a file on the phone, and optionally open the share sheet so the user can put it in Keep/Notes.",
        p("title", "Title / file name", true), p("text", "The text to save", true), p("share", "true to also open the share sheet", type = "BOOLEAN")) { ctx, a ->
        val text = a.optString("text"); if (text.isBlank()) return@tool ToolResult.fail("Save karne ke liye text to do.")
        val f = File(docsDir(ctx, "Notes"), "${safeName(a.optString("title"))}_${System.currentTimeMillis() / 1000}.txt")
        f.writeText(text)
        if (a.optBoolean("share", false)) launch(ctx, Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text).putExtra(Intent.EXTRA_SUBJECT, a.optString("title")), "Save to"))
        ToolResult.ok("Save ho gaya: ${f.absolutePath}") { put("path", f.absolutePath) }
    }

    private val deepResearch = tool("deep_research", "Research a topic properly: breaks it into questions, searches the live web for each, and writes a structured sourced report (saved as a file). Takes about a minute.",
        p("topic", "What to research", true), p("focus", "Angle or what the user wants to decide")) { ctx, a ->
        val topic = a.optString("topic").trim().ifEmpty { return@tool ToolResult.fail("Kis topic pe research karni hai?") }
        val plan = SubAgent.ask(ctx.app, "Break the research topic into 4 distinct, specific questions that together cover it (facts, current status, key players/numbers, risks or controversies). Output ONLY the 4 questions, one per line, no numbering.",
            "Topic: $topic\nFocus: ${a.optString("focus")}", temperature = 0.3)
        val qs = plan.text?.lines()?.map { it.trim().trimStart('-', '*', '•', ' ', '1', '2', '3', '4', '.', ')') }?.filter { it.length > 8 }?.take(4)
        if (qs.isNullOrEmpty()) return@tool ToolResult.fail(plan.error ?: "Research plan nahi ban paaya.")
        val findings = coroutineScope {
            qs.map { q -> async { q to SubAgent.ask(ctx.app, "Answer using current Google Search results. Give concrete facts, numbers, dates and name your sources. 5-8 sentences. If evidence is thin or conflicting, say so.", q, temperature = 0.2, googleSearch = true) } }.map { it.await() }
        }
        val ok = findings.filter { it.second.text != null }
        if (ok.isEmpty()) return@tool ToolResult.fail(findings.firstOrNull()?.second?.error ?: "Web se koi jawab nahi aaya.")
        val material = ok.joinToString("\n\n") { "Q: ${it.first}\nA: ${it.second.text}" }
        val rep = SubAgent.ask(ctx.app, "Write a clear research report from the findings below: a 3-line summary first, then sections with headings, key numbers, and a short 'Kya pakka hai / kya unclear hai' list. Use only the findings; do not invent facts. Write in the language of the topic (Hinglish if the topic is Hinglish).",
            "Topic: $topic\nFocus: ${a.optString("focus")}\n\n$material", temperature = 0.3)
        val report = rep.text ?: material
        val f = File(docsDir(ctx, "Research"), "${safeName(topic)}_${System.currentTimeMillis() / 1000}.txt"); f.writeText(report)
        val missing = if (ok.size < qs.size) "\n(${qs.size - ok.size} sawal ka jawab nahi mila.)" else ""
        val more = if (report.length > 3500) "\n...(poori report file me hai)" else ""
        ToolResult.ok(report.take(3500) + more + missing + "\nFile: ${f.absolutePath}") { put("path", f.absolutePath) }
    }

    private val generateImage = tool("generate_image", "Create an image from a description (poster, illustration, logo idea, wallpaper...). Opens it when ready.",
        p("prompt", "Detailed description of the image", true)) { ctx, a ->
        val r = ImageGen.run(ctx.app, a.optString("prompt").ifBlank { return@tool ToolResult.fail("Kaisi image chahiye wo batao.") })
        val f = r.file ?: return@tool ToolResult.fail(r.error ?: "Image nahi ban paayi.")
        uriFor(ctx, f)?.let { launch(ctx, Intent(Intent.ACTION_VIEW).setDataAndType(it, "image/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) }
        ToolResult.ok("Image ban gayi aur khol di: ${f.absolutePath}") { put("path", f.absolutePath) }
    }

    private val editPhoto = tool("edit_photo", "Edit a photo with an instruction (remove background, change colours, add an object, make it cartoon...). Use path='latest' for the newest gallery photo. Saves a new copy; the original is untouched.",
        p("instruction", "What to change", true), p("path", "Photo path, or 'latest' (default)")) { ctx, a ->
        val (src, err) = resolvePhoto(ctx, a.optString("path"))
        if (src == null) return@tool ToolResult.fail(err ?: "Photo nahi mili.")
        val r = ImageGen.run(ctx.app, a.optString("instruction").ifBlank { return@tool ToolResult.fail("Kya badalna hai wo batao.") }, src)
        val f = r.file ?: return@tool ToolResult.fail(r.error ?: "Photo edit nahi ho paayi.")
        uriFor(ctx, f)?.let { launch(ctx, Intent(Intent.ACTION_VIEW).setDataAndType(it, "image/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) }
        ToolResult.ok("Edit hui photo (nayi copy) khol di: ${f.absolutePath}") { put("path", f.absolutePath) }
    }

    private val generateQuoteImage = tool("generate_quote_image", "Make a shareable quote card image (1080x1080) from text, drawn on the phone - no internet needed.",
        p("text", "The quote", true), p("author", "Author / name shown below"), p("style", "dark, sunset, ocean, forest, light - default dark")) { ctx, a ->
        val text = a.optString("text").trim().ifEmpty { return@tool ToolResult.fail("Quote ka text do.") }
        val (c1, c2, fg) = when (a.optString("style").lowercase()) {
            "sunset" -> Triple(0xFFFF512F.toInt(), 0xFFDD2476.toInt(), Color.WHITE)
            "ocean" -> Triple(0xFF2193B0.toInt(), 0xFF6DD5ED.toInt(), Color.WHITE)
            "forest" -> Triple(0xFF134E5E.toInt(), 0xFF71B280.toInt(), Color.WHITE)
            "light" -> Triple(0xFFF5F7FA.toInt(), 0xFFC3CFE2.toInt(), 0xFF1F2937.toInt())
            else -> Triple(0xFF0F2027.toInt(), 0xFF2C5364.toInt(), Color.WHITE)
        }
        val size = 1080; val pad = 110
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888); val cv = Canvas(bmp)
        cv.drawRect(0f, 0f, size.toFloat(), size.toFloat(), Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = LinearGradient(0f, 0f, size.toFloat(), size.toFloat(), c1, c2, Shader.TileMode.CLAMP) })
        val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = fg; typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD) }
        val author = a.optString("author").trim()
        val maxH = size - 2 * pad - (if (author.isNotEmpty()) 120 else 0)
        var ts = 78f; var layout: StaticLayout
        do {
            tp.textSize = ts
            layout = StaticLayout.Builder.obtain(text, 0, text.length, tp, size - 2 * pad).setAlignment(Layout.Alignment.ALIGN_CENTER).setLineSpacing(0f, 1.2f).build()
            ts -= 4f
        } while (layout.height > maxH && ts > 26f)
        val totalH = layout.height + (if (author.isNotEmpty()) 110 else 0)
        cv.save(); cv.translate(pad.toFloat(), (size - totalH) / 2f); layout.draw(cv); cv.restore()
        if (author.isNotEmpty()) {
            val ap = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = fg; alpha = 200; textSize = 40f; textAlign = Paint.Align.CENTER; typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.ITALIC) }
            cv.drawText("— $author", size / 2f, (size + totalH) / 2f - 10f, ap)
        }
        val f = File(ImageGen.outDir(ctx.context), "quote_${System.currentTimeMillis()}.png")
        FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        uriFor(ctx, f)?.let { launch(ctx, Intent(Intent.ACTION_VIEW).setDataAndType(it, "image/png").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) }
        ToolResult.ok("Quote card ban gaya: ${f.absolutePath}") { put("path", f.absolutePath) }
    }

    // ---------------- email
    private val writeEmail = tool("write_email", "Draft an email (subject + body) from a short brief. It only writes the draft - nothing is sent.",
        p("brief", "What the email should say, to whom, and why", true), p("tone", "formal, friendly, firm, apologetic... default polite"), p("language", "default: language of the brief")) { ctx, a ->
        val ans = SubAgent.ask(ctx.app, "You write emails. Output exactly: a first line 'Subject: ...', a blank line, then the email body ready to send, with greeting and sign-off. No commentary. Do not invent facts, dates or numbers the brief does not contain; leave [brackets] for missing details.",
            "Brief: ${a.optString("brief")}\nTone: ${a.optString("tone").ifBlank { "polite and clear" }}\nLanguage: ${a.optString("language").ifBlank { "same as the brief" }}", temperature = 0.5)
        if (ans.text != null) ToolResult.ok(ans.text.trim()) else ToolResult.fail(ans.error ?: "Email draft nahi ban paaya.")
    }

    private val findEmailAddress = tool("find_email_address", "Find a contact's email address by name.", p("name", "Contact name", true)) { ctx, a ->
        if (ctx.context.checkSelfPermission(android.Manifest.permission.READ_CONTACTS) != android.content.pm.PackageManager.PERMISSION_GRANTED) return@tool ToolResult.fail("Contacts ki permission nahi hai.")
        val rows = ArrayList<String>()
        ctx.context.contentResolver.query(ContactsContract.CommonDataKinds.Email.CONTENT_URI, arrayOf(ContactsContract.CommonDataKinds.Email.DISPLAY_NAME, ContactsContract.CommonDataKinds.Email.ADDRESS),
            "${ContactsContract.CommonDataKinds.Email.DISPLAY_NAME} LIKE ?", arrayOf("%${a.optString("name")}%"), null)?.use { c -> while (c.moveToNext() && rows.size < 6) rows += "${c.getString(0)}: ${c.getString(1)}" }
        if (rows.isEmpty()) ToolResult.ok("'${a.optString("name")}' ka koi email address contacts me nahi mila.") else ToolResult.ok(rows.distinct().joinToString("\n"))
    }

    private fun mailIntent(to: String, subject: String, body: String, attach: Uri? = null): Intent {
        val i = Intent(if (attach != null) Intent.ACTION_SEND else Intent.ACTION_SENDTO)
        if (attach != null) { i.type = "message/rfc822"; i.putExtra(Intent.EXTRA_STREAM, attach); i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) } else i.data = Uri.parse("mailto:")
        return i.putExtra(Intent.EXTRA_EMAIL, to.split(",", ";").map { it.trim() }.filter { it.isNotEmpty() }.toTypedArray()).putExtra(Intent.EXTRA_SUBJECT, subject).putExtra(Intent.EXTRA_TEXT, body)
    }

    private val sendEmail = tool("send_email", "Open the email app with the recipient, subject and body filled in. The user taps Send (it is never sent without that).",
        p("to", "Recipient email(s), comma separated", true), p("subject", "Subject", true), p("body", "Body text", true)) { ctx, a ->
        if (launch(ctx, mailIntent(a.optString("to"), a.optString("subject"), a.optString("body")))) ToolResult.ok("Email app khul gayi - sab bhara hua hai, bhejne ke liye Send tum dabana.")
        else ToolResult.fail("Koi email app nahi mili.")
    }

    private val sendReport = tool("send_report", "Write a short report on something (or use the text given) and open the email app with it ready to send.",
        p("topic", "What the report is about, or leave empty if text is given"), p("text", "Ready report text to send as is"), p("to", "Recipient email(s)")) { ctx, a ->
        var body = a.optString("text").trim()
        if (body.isEmpty()) {
            val t = a.optString("topic").trim().ifEmpty { return@tool ToolResult.fail("Report kis baare me hai ya text do.") }
            val ans = SubAgent.ask(ctx.app, "Write a concise professional report with a title, 3-line summary, key points and next steps. Use only information in the request; mark anything missing with [brackets] instead of inventing it.", t, temperature = 0.4)
            body = ans.text?.trim() ?: return@tool ToolResult.fail(ans.error ?: "Report nahi ban paayi.")
        }
        val subject = body.lineSequence().firstOrNull { it.isNotBlank() }?.trim('#', '*', ' ')?.take(80) ?: "Report"
        if (launch(ctx, mailIntent(a.optString("to"), subject, body))) ToolResult.ok("Report email me bhar di - Send tum dabana.") { put("preview", body.take(300)) } else ToolResult.fail("Koi email app nahi mili.")
    }

    // ---------------- social
    private fun copyCaption(ctx: ToolContext, caption: String) {
        if (caption.isBlank()) return
        (ctx.context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("caption", caption))
    }

    private val PLATFORMS = mapOf("instagram" to "com.instagram.android", "facebook" to "com.facebook.katana", "twitter" to "com.twitter.android", "x" to "com.twitter.android",
        "whatsapp" to "com.whatsapp", "telegram" to "org.telegram.messenger", "threads" to "com.instagram.barcelona", "snapchat" to "com.snapchat.android")

    private val postPhotoToSocial = tool("post_photo_to_social", "Open a social app's share screen with a photo ready (the caption is copied so it can be pasted). The user taps Post. Use path='latest' for the newest gallery photo.",
        p("platform", "instagram, facebook, twitter, whatsapp, telegram, threads, snapchat", true), p("path", "Photo path or 'latest'"), p("caption", "Caption text")) { ctx, a ->
        val pkg = PLATFORMS[a.optString("platform").lowercase().trim()] ?: return@tool ToolResult.fail("Ye platform nahi samjha - ${PLATFORMS.keys.joinToString()} me se batao.")
        val (f, err) = resolvePhoto(ctx, a.optString("path"))
        if (f == null) return@tool ToolResult.fail(err ?: "Photo nahi mili.")
        val u = uriFor(ctx, f) ?: return@tool ToolResult.fail("Ye photo share nahi ho sakti.")
        val cap = a.optString("caption"); copyCaption(ctx, cap)
        val i = Intent(Intent.ACTION_SEND).setType("image/*").putExtra(Intent.EXTRA_STREAM, u).putExtra(Intent.EXTRA_TEXT, cap).setPackage(pkg).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        if (launch(ctx, i)) ToolResult.ok("${a.optString("platform")} khul gaya photo ke saath${if (cap.isNotBlank()) " - caption copy ho gaya, paste kar lena" else ""}. Post tum dabana.")
        else ToolResult.fail("${a.optString("platform")} app phone me install nahi hai ya share nahi le paayi.")
    }

    private val postStory = tool("post_story", "Open Instagram's story editor with a photo ready (the user taps Share). Use path='latest' for the newest gallery photo.",
        p("path", "Photo path or 'latest'"), p("caption", "Caption to copy for pasting")) { ctx, a ->
        val (f, err) = resolvePhoto(ctx, a.optString("path"))
        if (f == null) return@tool ToolResult.fail(err ?: "Photo nahi mili.")
        val u = uriFor(ctx, f) ?: return@tool ToolResult.fail("Ye photo share nahi ho sakti.")
        copyCaption(ctx, a.optString("caption"))
        val i = Intent("com.instagram.share.ADD_TO_STORY").setDataAndType(u, "image/*").setPackage("com.instagram.android").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        if (launch(ctx, i)) ToolResult.ok("Instagram story editor khul gaya - Share tum dabana.") else ToolResult.fail("Instagram install nahi hai ya story share nahi le paaya.")
    }
}
