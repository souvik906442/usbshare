package com.example.usbshare

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.util.LruCache
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private val ACTION_PERM = "com.example.usbshare.USB_PERMISSION"

    private lateinit var usb: UsbManager
    private var session: Session? = null

    private lateinit var statusView: TextView
    private lateinit var pathView: TextView
    private lateinit var listView: ListView
    private lateinit var adapter: ArrayAdapter<String>
    private lateinit var progressText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var logView: TextView
    private lateinit var tabLocal: Button
    private lateinit var tabRemote: Button
    private lateinit var tabFiles: Button
    private lateinit var tabMedia: Button
    private lateinit var tabRecent: Button
    private lateinit var subRecent: Button
    private lateinit var subAlbums: Button
    private lateinit var subRow: LinearLayout
    private lateinit var selBar: LinearLayout
    private lateinit var selAction: Button
    private lateinit var gridView: GridView
    private val gridAdapter = GridAdapter()

    // views: 0 = folders, 1 = photos & videos, 2 = recent files
    private var mode = 0
    private var mediaMode = 0            // 0 = newest first, 1 = albums
    private var openAlbum: String? = null
    private val selected = LinkedHashSet<String>()
    private val gridData = ArrayList<Any>()          // FileItem or Album
    private val listItems = ArrayList<FileItem>()    // rows of the Recent list
    private var localMedia: List<FileItem>? = null
    private var localRecent: List<FileItem>? = null
    private var remoteMedia: List<FileItem>? = null
    private var remoteRecent: List<FileItem>? = null
    private val localBusy = BooleanArray(2)
    private val remoteBusy = BooleanArray(2)
    private val localDirty = BooleanArray(2)
    private val dateFmt = SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault())

    private val thumbCache = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 1024 / 8).toInt()) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }
    private val thumbPending = HashSet<String>()
    private val thumbFail = HashSet<String>()
    private val thumbPool = Executors.newFixedThreadPool(3) { r -> Thread(r, "thumb").apply { isDaemon = true } }

    private class Album(val dir: String, val name: String, val items: List<FileItem>)

    private val logLines = mutableListOf<String>()
    private var showRemote = false
    private var localDir: File = Environment.getExternalStorageDirectory()
    private var localEntries: List<Entry> = emptyList()
    private var remotePath = ""
    private var remoteRoot = ""
    private var remoteEntries: List<Entry> = emptyList()
    private val shown = ArrayList<Entry?>()   // null = the ".." row
    private var permCallback: ((Boolean) -> Unit)? = null
    private var connecting = false

    private var progName = ""
    private var progStart = 0L
    private var progDone = 0L

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private val inbox: File
        get() = File(Environment.getExternalStorageDirectory(), "Download/UsbShare")

    // ------------------------------------------------------------------ receivers

    private val permReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val granted = i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
            val cb = permCallback
            permCallback = null
            cb?.invoke(granted)
        }
    }

    private val detachReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            session?.close()
        }
    }

    private fun reg(r: BroadcastReceiver, f: IntentFilter) {
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(r, f, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(r, f)
        }
    }

    // ------------------------------------------------------------------ session listener

    private val listener = object : Session.Listener {
        override fun onLog(msg: String) {
            runOnUiThread { log(msg) }
        }

        override fun onRemoteList(path: String, entries: List<Entry>) {
            runOnUiThread {
                remotePath = path
                if (remoteRoot.isEmpty()) remoteRoot = path
                remoteEntries = ArrayList(entries)
                if (showRemote) refresh()
            }
        }

        override fun onRemoteError(msg: String) {
            runOnUiThread { log("Other phone: $msg") }
        }

        override fun onRemoteScan(kind: Int, items: List<FileItem>) {
            runOnUiThread {
                if (kind !in 0..1) return@runOnUiThread
                remoteBusy[kind] = false
                if (kind == Session.SCAN_MEDIA) remoteMedia = ArrayList(items) else remoteRecent = ArrayList(items)
                if (showRemote) refresh()
            }
        }

        override fun onThumb(path: String, jpeg: ByteArray) {
            runOnUiThread {
                val k = "R:" + path
                thumbPending.remove(k)
                val bm = if (jpeg.isEmpty()) null else BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
                if (bm == null) {
                    thumbFail.add(k)
                } else {
                    thumbCache.put(k, bm)
                    applyThumb(k, bm)
                }
            }
        }

        override fun onProgress(name: String, done: Long, total: Long, sending: Boolean) {
            runOnUiThread {
                val now = System.currentTimeMillis()
                if (name != progName || done < progDone) {
                    progName = name
                    progStart = now - 1
                }
                progDone = done
                val mbps = done.toDouble() / (now - progStart).coerceAtLeast(1) / 1000.0
                progressText.text = (if (sending) "Sending " else "Receiving ") + name + "\n" +
                    fmt(done) + " / " + fmt(total) + "   " + String.format("%.1f", mbps) + " MB/s"
                progressBar.progress = if (total > 0) (done * 1000 / total).toInt() else 0
            }
        }

        override fun onFileReceived(relPath: String, size: Long, saved: File) {
            runOnUiThread { log("Saved: " + saved.path) }
        }

        override fun onBatchDone(sending: Boolean, files: Int, bytes: Long, ms: Long) {
            runOnUiThread {
                val mbps = bytes.toDouble() / ms / 1000.0
                val text = (if (sending) "Sent " else "Received ") + files + " file(s), " + fmt(bytes) +
                    " in " + String.format("%.1f", ms / 1000.0) + " s (" + String.format("%.1f", mbps) + " MB/s)"
                log(text)
                Toast.makeText(this@MainActivity, text, Toast.LENGTH_LONG).show()
                progressBar.progress = 0
                progressText.text = text
                if (!sending) {
                    // new files arrived: the photo / recent lists of this phone are out of date
                    localDirty[0] = true
                    localDirty[1] = true
                    if (!showRemote && mode != 0) refresh()
                }
            }
        }

        override fun onClosed(reason: String) {
            runOnUiThread {
                session = null
                connecting = false
                remoteEntries = emptyList()
                remotePath = ""
                remoteRoot = ""
                resetRemoteScans()
                status("○ Disconnected: $reason")
                refresh()
            }
        }
    }

    // ------------------------------------------------------------------ UI

    private fun btn(label: String, onClick: () -> Unit): Button {
        val b = Button(this)
        b.text = label
        b.isAllCaps = false
        b.setTextColor(Color.WHITE)
        b.setBackgroundColor(0xFF3A3A3A.toInt())
        b.setOnClickListener { onClick() }
        return b
    }

    private fun row(vararg views: View): LinearLayout {
        val r = LinearLayout(this)
        r.orientation = LinearLayout.HORIZONTAL
        for (v in views) {
            val lp = LinearLayout.LayoutParams(0, dp(46), 1f)
            lp.setMargins(dp(2), dp(2), dp(2), dp(2))
            r.addView(v, lp)
        }
        return r
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        usb = getSystemService(Context.USB_SERVICE) as UsbManager

        statusView = TextView(this)
        statusView.textSize = 14f
        statusView.setTextColor(Color.WHITE)
        statusView.setPadding(dp(4), dp(2), dp(4), dp(6))

        tabLocal = btn("This phone") { showRemote = false; resetSel(); refresh() }
        tabRemote = btn("Other phone") { showRemote = true; resetSel(); refresh() }
        tabFiles = btn("📁 Files") { selectMode(0) }
        tabMedia = btn("🖼 Media") { selectMode(1) }
        tabRecent = btn("🕒 Recent") { selectMode(2) }
        subRecent = btn("Recent") { mediaMode = 0; openAlbum = null; selected.clear(); refresh() }
        subAlbums = btn("Albums") { mediaMode = 1; openAlbum = null; selected.clear(); refresh() }
        subRow = row(subRecent, subAlbums)
        subRow.visibility = View.GONE
        selAction = btn("") { sendSelected() }
        selBar = row(selAction, btn("Clear selection") { selected.clear(); refresh() })
        selBar.visibility = View.GONE

        pathView = TextView(this)
        pathView.textSize = 12f
        pathView.setTextColor(0xFFBBBBBB.toInt())
        pathView.setPadding(dp(4), dp(4), dp(4), dp(4))

        adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, ArrayList<String>())
        listView = ListView(this)
        listView.adapter = adapter
        listView.setOnItemClickListener { _, _, pos, _ ->
            if (mode == 0) {
                if (pos < shown.size) {
                    val e = shown[pos]
                    if (e == null) goUp() else if (e.dir) openDir(e) else itemMenu(e)
                }
            } else if (pos < listItems.size) {
                val fi = listItems[pos]
                if (selected.isNotEmpty()) toggleSel(fi.path) else pathMenu(fi.path, fi.name())
            }
        }
        listView.setOnItemLongClickListener { _, _, pos, _ ->
            if (mode == 0) {
                if (pos < shown.size) {
                    val e = shown[pos]
                    if (e != null) itemMenu(e)
                }
            } else if (pos < listItems.size) {
                toggleSel(listItems[pos].path)
            }
            true
        }

        gridView = GridView(this)
        gridView.numColumns = if (resources.configuration.screenWidthDp >= 600) 5 else 3
        gridView.horizontalSpacing = dp(2)
        gridView.verticalSpacing = dp(2)
        gridView.stretchMode = GridView.STRETCH_COLUMN_WIDTH
        gridView.adapter = gridAdapter
        gridView.visibility = View.GONE
        gridView.setOnItemClickListener { _, _, pos, _ ->
            val d = gridData.getOrNull(pos)
            if (d is Album) {
                openAlbum = d.dir
                selected.clear()
                refresh()
            } else if (d is FileItem) {
                toggleSel(d.path)
            }
        }
        gridView.setOnItemLongClickListener { _, _, pos, _ ->
            val d = gridData.getOrNull(pos)
            if (d is FileItem) toggleSel(d.path)
            true
        }
        val listFrame = FrameLayout(this)
        listFrame.addView(listView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        listFrame.addView(gridView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        progressText = TextView(this)
        progressText.textSize = 12f
        progressText.setTextColor(0xFFBBBBBB.toInt())
        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
        progressBar.max = 1000
        logView = TextView(this)
        logView.textSize = 11f
        logView.setTextColor(0xFF888888.toInt())

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(0xFF111111.toInt())
        root.setPadding(dp(8), dp(8), dp(8), dp(8))
        root.addView(statusView)
        root.addView(row(
            btn("Connect (this phone = host)") { startHost() },
            btn("Disconnect") { session?.close() },
            btn("Storage access") { askStorage() }
        ))
        root.addView(row(tabLocal, tabRemote))
        root.addView(row(tabFiles, tabMedia, tabRecent))
        root.addView(subRow)
        root.addView(selBar)
        root.addView(pathView)
        root.addView(listFrame, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(progressText)
        root.addView(progressBar)
        root.addView(logView)
        setContentView(root)

        reg(permReceiver, IntentFilter(ACTION_PERM))
        reg(detachReceiver, IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            addAction(UsbManager.ACTION_USB_ACCESSORY_DETACHED)
        })

        status("○ Not connected. Plug the cable, then tap Connect on ONE phone (the host).")
        refresh()
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        if (hasStorageAccess()) loadLocal() else status("Storage access needed: tap 'Storage access'.")
        if (session == null && !connecting) {
            val acc = usb.accessoryList?.firstOrNull()
            if (acc != null) openAccessory(acc)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        session?.close()
        thumbPool.shutdownNow()
        try { unregisterReceiver(permReceiver) } catch (e: Exception) { }
        try { unregisterReceiver(detachReceiver) } catch (e: Exception) { }
    }

    private fun status(s: String) {
        statusView.text = s
    }

    private fun log(s: String) {
        logLines.add(s)
        while (logLines.size > 5) logLines.removeAt(0)
        logView.text = logLines.joinToString("\n")
    }

    private fun fmt(n: Long): String = when {
        n < 1024 -> "$n B"
        n < 1024 * 1024 -> String.format("%.1f KB", n / 1024.0)
        n < 1024L * 1024 * 1024 -> String.format("%.1f MB", n / 1048576.0)
        else -> String.format("%.2f GB", n / 1073741824.0)
    }

    // ------------------------------------------------------------------ file lists

    private fun loadLocal() {
        val list = Session.listLocal(localDir)
        localEntries = list ?: emptyList()
        if (list == null) log("Cannot read " + localDir.path)
        refresh()
    }

    private fun hi(b: Button, on: Boolean) {
        b.setBackgroundColor(if (on) 0xFF2E7D32.toInt() else 0xFF3A3A3A.toInt())
    }

    private fun refresh() {
        hi(tabLocal, !showRemote)
        hi(tabRemote, showRemote)
        hi(tabFiles, mode == 0)
        hi(tabMedia, mode == 1)
        hi(tabRecent, mode == 2)
        hi(subRecent, mediaMode == 0)
        hi(subAlbums, mediaMode == 1)
        subRow.visibility = if (mode == 1) View.VISIBLE else View.GONE
        when (mode) {
            0 -> refreshFiles()
            1 -> refreshMedia()
            else -> refreshRecent()
        }
        selBar.visibility = if (mode != 0 && selected.isNotEmpty()) View.VISIBLE else View.GONE
        selAction.text = (if (showRemote) "Get " else "Send ") + selected.size + " file(s)"
    }

    private fun src(): String = if (showRemote) "Other phone: " else "This phone: "

    private fun refreshFiles() {
        listView.visibility = View.VISIBLE
        gridView.visibility = View.GONE
        val rootPath = Environment.getExternalStorageDirectory().absolutePath
        val entries: List<Entry>
        val path: String
        val atRoot: Boolean
        if (showRemote) {
            entries = remoteEntries
            path = if (session == null) "(not connected)" else remotePath
            atRoot = session == null || remotePath.isEmpty() || remotePath == remoteRoot
        } else {
            entries = localEntries
            path = localDir.absolutePath
            atRoot = path == rootPath
        }
        pathView.text = src() + path
        shown.clear()
        val labels = ArrayList<String>()
        if (!atRoot) {
            shown.add(null)
            labels.add("⬆  ..")
        }
        for (e in entries) {
            shown.add(e)
            labels.add((if (e.dir) "📁 " else "📄 ") + e.name + (if (e.dir) "" else "   " + fmt(e.size)))
        }
        adapter.clear()
        adapter.addAll(labels)
        adapter.notifyDataSetChanged()
    }

    private fun folderLabel(fi: FileItem): String {
        val rel = fi.parent().removePrefix(Environment.getExternalStorageDirectory().absolutePath).trim('/')
        return if (rel.isEmpty()) "Internal storage" else rel
    }

    private fun refreshRecent() {
        listView.visibility = View.VISIBLE
        gridView.visibility = View.GONE
        ensureScan(Session.SCAN_RECENT, false)
        val items = if (showRemote) remoteRecent else localRecent
        pathView.text = src() + when {
            showRemote && session == null -> "(not connected)"
            items == null -> "Recent files - loading…"
            else -> "Recent files, newest first (" + items.size + ")  - tap this tab again to reload"
        }
        shown.clear()
        listItems.clear()
        val labels = ArrayList<String>()
        if (items != null) {
            for (fi in items) {
                listItems.add(fi)
                val icon = if (selected.contains(fi.path)) "✅ " else when (fi.type) {
                    FileItem.IMAGE -> "🖼 "
                    FileItem.VIDEO -> "🎞 "
                    else -> "📄 "
                }
                labels.add(icon + fi.name() + "\n" + dateFmt.format(Date(fi.mtime)) + "  •  " + fmt(fi.size) + "  •  " + folderLabel(fi))
            }
        }
        adapter.clear()
        adapter.addAll(labels)
        adapter.notifyDataSetChanged()
    }

    private fun refreshMedia() {
        listView.visibility = View.GONE
        gridView.visibility = View.VISIBLE
        ensureScan(Session.SCAN_MEDIA, false)
        val items = if (showRemote) remoteMedia else localMedia
        gridData.clear()
        val title: String
        if (showRemote && session == null) {
            title = "(not connected)"
        } else if (items == null) {
            title = "Photos & videos - loading…"
        } else if (mediaMode == 0) {
            gridData.addAll(items)
            title = "Photos & videos, newest first (" + items.size + ")"
        } else if (openAlbum == null) {
            val map = LinkedHashMap<String, ArrayList<FileItem>>()
            for (fi in items) map.getOrPut(fi.parent()) { ArrayList() }.add(fi)
            for ((dir, list) in map) gridData.add(Album(dir, dir.substringAfterLast('/'), list))
            title = "Albums (" + map.size + ")"
        } else {
            val dir = openAlbum!!
            val inAlbum = items.filter { it.parent() == dir }
            gridData.addAll(inAlbum)
            title = "Album: " + dir.substringAfterLast('/') + " (" + inAlbum.size + ")  - Back / Albums to close"
        }
        pathView.text = src() + title
        gridAdapter.notifyDataSetChanged()
    }

    private fun goUp() {
        if (showRemote) {
            val parent = File(remotePath).parent ?: ""
            session?.requestList(parent)
        } else {
            val p = localDir.parentFile
            if (p != null) {
                localDir = p
                loadLocal()
            }
        }
    }

    private fun openDir(e: Entry) {
        if (showRemote) {
            session?.requestList(remotePath + "/" + e.name)
        } else {
            localDir = File(localDir, e.name)
            loadLocal()
        }
    }

    private fun itemMenu(e: Entry) {
        val label = if (showRemote) "Get (save to Download/UsbShare)" else "Send to other phone"
        AlertDialog.Builder(this)
            .setTitle(e.name)
            .setItems(arrayOf(label)) { _, _ -> act(e) }
            .show()
    }

    private fun act(e: Entry) {
        val s = session
        if (s == null) {
            Toast.makeText(this, "Not connected", Toast.LENGTH_SHORT).show()
            return
        }
        if (showRemote) {
            s.requestGet(remotePath + "/" + e.name)
            log("Requested: " + e.name)
        } else {
            s.sendFiles(listOf(File(localDir, e.name)))
            log("Sending: " + e.name)
        }
    }

    // ------------------------------------------------------------------ photo / recent helpers

    private fun resetSel() {
        selected.clear()
        openAlbum = null
    }

    private fun selectMode(m: Int) {
        val again = mode == m
        mode = m
        selected.clear()
        if (m != 1) openAlbum = null
        refresh()
        if (again && m != 0) ensureScan(if (m == 1) Session.SCAN_MEDIA else Session.SCAN_RECENT, true)
    }

    private fun resetRemoteScans() {
        remoteMedia = null
        remoteRecent = null
        remoteBusy.fill(false)
        thumbPending.removeAll { it.startsWith("R:") }
        thumbFail.removeAll { it.startsWith("R:") }
        if (showRemote) selected.clear()
    }

    /** Loads the photo list (SCAN_MEDIA) or the newest-files list (SCAN_RECENT) of the phone being shown. */
    private fun ensureScan(kind: Int, force: Boolean) {
        if (showRemote) {
            val s = session ?: return
            val have = if (kind == Session.SCAN_MEDIA) remoteMedia else remoteRecent
            if ((have == null || force) && !remoteBusy[kind]) {
                remoteBusy[kind] = true
                s.requestScan(kind)
            }
        } else {
            if (!hasStorageAccess()) return
            val have = if (kind == Session.SCAN_MEDIA) localMedia else localRecent
            if ((have == null || force || localDirty[kind]) && !localBusy[kind]) {
                localBusy[kind] = true
                localDirty[kind] = false
                val t = Thread {
                    val r = Session.scanLocal(Environment.getExternalStorageDirectory(), kind)
                    runOnUiThread {
                        localBusy[kind] = false
                        if (kind == Session.SCAN_MEDIA) localMedia = r else localRecent = r
                        refresh()
                    }
                }
                t.isDaemon = true
                t.start()
            }
        }
    }

    private fun toggleSel(path: String) {
        if (!selected.remove(path)) selected.add(path)
        val first = listView.firstVisiblePosition
        val top = listView.getChildAt(0)?.top ?: 0
        refresh()
        if (mode == 2) listView.setSelectionFromTop(first, top)
    }

    private fun transfer(paths: List<String>) {
        val s = session
        if (s == null) {
            Toast.makeText(this, "Not connected", Toast.LENGTH_SHORT).show()
            return
        }
        if (showRemote) {
            s.requestGetMany(paths)
            log("Requested: " + paths.size + " file(s)")
        } else {
            s.sendFiles(paths.map { File(it) })
            log("Sending: " + paths.size + " file(s)")
        }
    }

    private fun sendSelected() {
        if (selected.isEmpty()) return
        transfer(selected.toList())
        selected.clear()
        refresh()
    }

    private fun pathMenu(path: String, name: String) {
        val label = if (showRemote) "Get (save to Download/UsbShare)" else "Send to other phone"
        AlertDialog.Builder(this)
            .setTitle(name)
            .setItems(arrayOf(label)) { _, _ -> transfer(listOf(path)) }
            .show()
    }

    private fun tkey(path: String): String = (if (showRemote) "R:" else "L:") + path

    private fun requestThumb(fi: FileItem, k: String) {
        if (thumbFail.contains(k) || !thumbPending.add(k)) return
        if (showRemote) {
            val s = session
            if (s == null) thumbPending.remove(k) else s.requestThumb(fi.path)
        } else {
            thumbPool.execute {
                val bm = Thumbs.load(File(fi.path), fi.type, Session.THUMB_PX)
                runOnUiThread {
                    thumbPending.remove(k)
                    if (bm == null) {
                        thumbFail.add(k)
                    } else {
                        thumbCache.put(k, bm)
                        applyThumb(k, bm)
                    }
                }
            }
        }
    }

    private fun applyThumb(k: String, bm: Bitmap) {
        for (i in 0 until gridView.childCount) {
            val c = gridView.getChildAt(i)
            if (c is Cell && c.tk == k) c.iv.setImageBitmap(bm)
        }
    }

    private inner class Cell(c: Context) : FrameLayout(c) {
        val iv = ImageView(c)
        val badge = TextView(c)
        val label = TextView(c)
        val check = TextView(c)
        var tk: String? = null

        init {
            iv.scaleType = ImageView.ScaleType.CENTER_CROP
            iv.setBackgroundColor(0xFF2A2A2A.toInt())
            addView(iv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            badge.text = "▶"
            badge.textSize = 12f
            badge.setTextColor(Color.WHITE)
            badge.setBackgroundColor(0x99000000.toInt())
            badge.setPadding(dp(5), dp(1), dp(5), dp(1))
            addView(badge, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.START))
            label.textSize = 12f
            label.setTextColor(Color.WHITE)
            label.setBackgroundColor(0xBB000000.toInt())
            label.setPadding(dp(6), dp(3), dp(6), dp(3))
            label.maxLines = 1
            label.ellipsize = android.text.TextUtils.TruncateAt.END
            addView(label, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
            check.text = "✓"
            check.textSize = 16f
            check.gravity = Gravity.CENTER
            check.setTextColor(Color.WHITE)
            check.setBackgroundColor(0xFF2E7D32.toInt())
            addView(check, FrameLayout.LayoutParams(dp(26), dp(26), Gravity.TOP or Gravity.END))
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            super.onMeasure(widthMeasureSpec, widthMeasureSpec)   // square cell
        }

        fun bind(fi: FileItem, lbl: String?) {
            val k = tkey(fi.path)
            tk = k
            val isAlbum = lbl != null
            badge.visibility = if (!isAlbum && fi.type == FileItem.VIDEO) View.VISIBLE else View.GONE
            if (lbl != null) {
                label.text = lbl
                label.visibility = View.VISIBLE
            } else {
                label.visibility = View.GONE
            }
            val sel = !isAlbum && selected.contains(fi.path)
            check.visibility = if (sel) View.VISIBLE else View.GONE
            iv.alpha = if (sel) 0.55f else 1f
            val bm = thumbCache.get(k)
            if (bm != null) {
                iv.setImageBitmap(bm)
            } else {
                iv.setImageDrawable(null)
                requestThumb(fi, k)
            }
        }
    }

    private inner class GridAdapter : BaseAdapter() {
        override fun getCount(): Int = gridData.size
        override fun getItem(position: Int): Any = gridData[position]
        override fun getItemId(position: Int): Long = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val cell = (convertView as? Cell) ?: Cell(this@MainActivity)
            val d = gridData[position]
            if (d is Album) {
                cell.bind(d.items[0], d.name + " (" + d.items.size + ")")
            } else {
                cell.bind(d as FileItem, null)
            }
            return cell
        }
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        if (selected.isNotEmpty()) {
            selected.clear()
            refresh()
        } else if (mode == 1 && mediaMode == 1 && openAlbum != null) {
            openAlbum = null
            refresh()
        } else {
            super.onBackPressed()
        }
    }

    // ------------------------------------------------------------------ storage permission

    private fun hasStorageAccess(): Boolean =
        if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
        else checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    private fun askStorage() {
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
            } catch (e: Exception) {
                startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
        } else {
            requestPermissions(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE), 1)
        }
    }

    // ------------------------------------------------------------------ USB permission

    private fun permIntent(): PendingIntent {
        val flags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
        return PendingIntent.getBroadcast(this, 0, Intent(ACTION_PERM).setPackage(packageName), flags)
    }

    private fun withPermission(dev: UsbDevice, cb: (Boolean) -> Unit) {
        if (usb.hasPermission(dev)) {
            cb(true)
            return
        }
        permCallback = cb
        usb.requestPermission(dev, permIntent())
    }

    private fun withAccessoryPermission(acc: UsbAccessory, cb: (Boolean) -> Unit) {
        if (usb.hasPermission(acc)) {
            cb(true)
            return
        }
        permCallback = cb
        usb.requestPermission(acc, permIntent())
    }

    // ------------------------------------------------------------------ host side

    private fun isAccessoryDevice(d: UsbDevice) = d.vendorId == 0x18D1 && d.productId in 0x2D00..0x2D05

    private fun startHost() {
        if (session != null) {
            Toast.makeText(this, "Already connected", Toast.LENGTH_SHORT).show()
            return
        }
        if (connecting) return
        if (!hasStorageAccess()) {
            askStorage()
            return
        }
        val devs = usb.deviceList.values.toList()
        val already = devs.firstOrNull { isAccessoryDevice(it) }
        if (already != null) {
            connectAccessoryDevice(already)
            return
        }
        val other = devs.firstOrNull()
        if (other == null) {
            status("No USB device seen. Check the cable, or make THIS phone the host: pull down the USB notification → 'USB controlled by' → This device (or swap the phones).")
            return
        }
        connecting = true
        status("Asking the other phone to switch to accessory mode…")
        withPermission(other) { ok ->
            if (!ok) {
                connecting = false
                status("USB permission denied.")
                return@withPermission
            }
            val t = Thread {
                val good = try { aoaHandshake(other) } catch (e: Exception) { false }
                if (!good) {
                    runOnUiThread {
                        connecting = false
                        status("The other phone did not enter accessory mode (is it unlocked? does it support AOA?).")
                    }
                    return@Thread
                }
                var found: UsbDevice? = null
                for (i in 0 until 40) {
                    try { Thread.sleep(250) } catch (e: InterruptedException) { }
                    found = usb.deviceList.values.firstOrNull { isAccessoryDevice(it) }
                    if (found != null) break
                }
                val f = found
                runOnUiThread {
                    if (f == null) {
                        connecting = false
                        status("Accessory device did not appear. Unplug, replug and try again.")
                    } else {
                        connectAccessoryDevice(f)
                    }
                }
            }
            t.isDaemon = true
            t.start()
        }
    }

    /** Android Open Accessory handshake: GET_PROTOCOL, 6 identification strings, START. */
    private fun aoaHandshake(dev: UsbDevice): Boolean {
        val conn = usb.openDevice(dev) ?: return false
        try {
            val buf = ByteArray(2)
            val r = conn.controlTransfer(0xC0, 51, 0, 0, buf, 2, 2000)
            if (r < 2) return false
            val ver = ((buf[1].toInt() and 0xFF) shl 8) or (buf[0].toInt() and 0xFF)
            if (ver < 1) return false
            val strs = arrayOf("UsbShare", "FileXfer", "USB file transfer", "1.0", "https://example.com/usbshare", "0001")
            for (i in strs.indices) {
                val b = (strs[i] + "\u0000").toByteArray()
                if (conn.controlTransfer(0x40, 52, 0, i, b, b.size, 2000) < 0) return false
            }
            return conn.controlTransfer(0x40, 53, 0, 0, null, 0, 2000) >= 0
        } finally {
            conn.close()
        }
    }

    private fun connectAccessoryDevice(dev: UsbDevice) {
        connecting = true
        status("Opening the USB link…")
        withPermission(dev) { ok ->
            if (!ok) {
                connecting = false
                status("USB permission denied.")
                return@withPermission
            }
            val t = Thread {
                val tr = openAccessoryDevice(dev)
                runOnUiThread {
                    if (tr == null) {
                        connecting = false
                        status("Could not open the accessory interface.")
                    } else {
                        startSession(tr, "host")
                    }
                }
            }
            t.isDaemon = true
            t.start()
        }
    }

    private fun openAccessoryDevice(dev: UsbDevice): HostTransport? {
        val conn = usb.openDevice(dev) ?: return null
        for (i in 0 until dev.interfaceCount) {
            val intf = dev.getInterface(i)
            if (intf.interfaceSubclass == 0x42) continue   // skip the adb interface of PID 2D01
            var inEp: UsbEndpoint? = null
            var outEp: UsbEndpoint? = null
            for (e in 0 until intf.endpointCount) {
                val ep = intf.getEndpoint(e)
                if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                    if (ep.direction == UsbConstants.USB_DIR_IN) inEp = ep else outEp = ep
                }
            }
            if (inEp != null && outEp != null && conn.claimInterface(intf, true)) {
                return HostTransport(conn, intf, inEp, outEp)
            }
        }
        conn.close()
        return null
    }

    // ------------------------------------------------------------------ accessory side

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        if (intent.action == UsbManager.ACTION_USB_ACCESSORY_ATTACHED) {
            @Suppress("DEPRECATION")
            val acc = intent.getParcelableExtra<UsbAccessory>(UsbManager.EXTRA_ACCESSORY)
            if (acc != null) openAccessory(acc)
        }
    }

    private fun openAccessory(acc: UsbAccessory) {
        if (session != null) return
        if (!hasStorageAccess()) {
            status("Storage access needed first: tap 'Storage access', then replug the cable.")
            return
        }
        if (!usb.hasPermission(acc)) {
            withAccessoryPermission(acc) { ok -> if (ok) openAccessory(acc) }
            return
        }
        val pfd = usb.openAccessory(acc)
        if (pfd == null) {
            status("Could not open the accessory.")
            return
        }
        connecting = true
        startSession(AccessoryTransport(pfd), "accessory")
    }

    // ------------------------------------------------------------------ session

    private fun startSession(tr: Transport, who: String) {
        inbox.mkdirs()
        val s = Session(tr, Environment.getExternalStorageDirectory(), inbox, Build.MANUFACTURER + " " + Build.MODEL, listener)
        session = s
        connecting = false
        s.start()
        status("● Connected ($who). Received files go to Download/UsbShare")
        showRemote = true
        remotePath = ""
        remoteRoot = ""
        remoteEntries = emptyList()
        resetRemoteScans()
        selected.clear()
        openAlbum = null
        refresh()
        s.requestList("")
    }
}
