package fr.leslapibreizh.mediatheque

import android.Manifest
import android.app.AlertDialog
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors

/**
 * V0.2 : lecture seule des photos et videos MediaStore, classement logique dans
 * les preferences (aucun deplacement ou suppression des originaux).
 */
class MainActivity : AppCompatActivity() {
    private val gold = Color.rgb(207, 174, 104)
    private val cream = Color.rgb(246, 232, 204)
    private val black = Color.rgb(8, 7, 5)
    private val background = Color.rgb(29, 27, 23)
    private val io = Executors.newFixedThreadPool(3)
    private val bitmapCache = object : LruCache<String, Bitmap>(12 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }
    private val prefs by lazy { getSharedPreferences("lapibreizh_categories_v02", MODE_PRIVATE) }
    private val selected = linkedSetOf<String>()
    private var route = Route.HOME
    private var galleryItems = emptyList<Media>()
    private var shownItems = emptyList<Media>()
    private var albumFilter: String? = null
    private var categoryFilter: String? = null
    private var sortedNewestFirst = true
    private var visibleLimit = 120
    private var grid: GridView? = null
    private var galleryAdapter: BaseAdapter? = null
    private var selectionActions: LinearLayout? = null
    private var selectionInfo: TextView? = null
    private var generation = 0

    private enum class Route { HOME, IMAGES, VIDEOS, UNSORTED, SETTINGS }
    private data class Media(
        val id: Long,
        val uri: Uri,
        val mime: String,
        val title: String,
        val album: String,
        val date: Long,
        val size: Long
    ) {
        val key: String get() = "${if (mime.startsWith("video/")) "v" else "i"}:$id"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = black
        window.navigationBarColor = black
        showHome()
    }

    private fun root(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(black)
        setPadding(dp(14), dp(18), dp(14), dp(10))
    }

    private fun heading(text: String, size: Float = 23f) = TextView(this).apply {
        this.text = text
        textSize = size
        setTextColor(gold)
        gravity = Gravity.CENTER
        setPadding(dp(4), dp(9), dp(4), dp(12))
    }

    private fun note(text: String) = TextView(this).apply {
        this.text = text
        textSize = 14f
        setTextColor(cream)
        gravity = Gravity.CENTER
        setPadding(dp(4), dp(6), dp(4), dp(8))
    }

    private fun action(text: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        textSize = 13f
        isAllCaps = false
        setTextColor(cream)
        backgroundTintList = android.content.res.ColorStateList.valueOf(this@MainActivity.background)
        setOnClickListener { onClick() }
    }

    private fun dp(n: Int) = (resources.displayMetrics.density * n + 0.5f).toInt()

    private fun showHome() {
        route = Route.HOME
        selected.clear()
        val layout = root()
        val logo = ImageView(this).apply {
            setImageResource(R.drawable.lapibreizh_icon)
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = "Les deux lapins Lapibreizh, logo de la médiathèque"
        }
        layout.addView(logo, LinearLayout.LayoutParams(-1, dp(168)))
        layout.addView(heading("LA MÉDIATHÈQUE", 23f))
        layout.addView(note("Images • Vidéos • Créations"))
        listOf(
            "MES IMAGES" to Route.IMAGES,
            "MONTAGES VIDÉO — EDITS" to Route.VIDEOS,
            "À CLASSER" to Route.UNSORTED,
            "PARAMÈTRES" to Route.SETTINGS
        ).forEach { (title, target) ->
            val b = action(title) { navigate(target) }
            layout.addView(b, LinearLayout.LayoutParams(-1, dp(66)).apply { topMargin = dp(9) })
        }
        layout.addView(note("Version 0.2 · consultation sans suppression ni déplacement"))
        setContentView(layout)
    }

    private fun navigate(next: Route) {
        if (next == Route.SETTINGS) { showSettings(); return }
        if (!hasPermission(next)) {
            route = next
            val requested = if (Build.VERSION.SDK_INT >= 34) {
                arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO,
                    Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
            } else if (Build.VERSION.SDK_INT >= 33) {
                arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
            } else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
            ActivityCompat.requestPermissions(this, requested, 150)
            return
        }
        showGallery(next)
    }

    private fun hasPermission(target: Route): Boolean {
        if (Build.VERSION.SDK_INT < 33) return allowed(Manifest.permission.READ_EXTERNAL_STORAGE)
        val selectedAllowed = Build.VERSION.SDK_INT >= 34 && allowed(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        return when (target) {
            Route.IMAGES -> allowed(Manifest.permission.READ_MEDIA_IMAGES) || selectedAllowed
            Route.VIDEOS -> allowed(Manifest.permission.READ_MEDIA_VIDEO) || selectedAllowed
            Route.UNSORTED -> allowed(Manifest.permission.READ_MEDIA_IMAGES) ||
                allowed(Manifest.permission.READ_MEDIA_VIDEO) || selectedAllowed
            else -> true
        }
    }

    private fun allowed(permission: String) =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    @Deprecated("Android's legacy permissions callback is supported for Android 8-12")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 150) {
            if (hasPermission(route)) showGallery(route)
            else AlertDialog.Builder(this).setTitle("Accès aux médias")
                .setMessage("Autorise les photos ou vidéos pour afficher ta bibliothèque. Aucun fichier ne sera modifié.")
                .setPositiveButton("Compris") { _, _ -> showHome() }
                .show()
        }
    }

    private fun showGallery(next: Route) {
        route = next
        selected.clear()
        albumFilter = null
        categoryFilter = null
        visibleLimit = 120
        loadGallery()
    }

    private fun loadGallery() {
        val requestNumber = ++generation
        val layout = root()
        val title = when (route) {
            Route.IMAGES -> "MES IMAGES"
            Route.VIDEOS -> "MONTAGES VIDÉO — EDITS"
            else -> "À CLASSER"
        }
        val toolbar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        toolbar.addView(action("‹ Retour") { showHome() }, LinearLayout.LayoutParams(dp(100), dp(48)))
        toolbar.addView(heading(title, 18f), LinearLayout.LayoutParams(0, dp(48), 1f))
        layout.addView(toolbar)
        val info = note("Chargement des médias…")
        selectionInfo = info
        layout.addView(info)
        val filters = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        filters.addView(action("Albums") { chooseAlbum() }, LinearLayout.LayoutParams(0, dp(50), 1f))
        filters.addView(action("Catégories") { chooseCategory() }, LinearLayout.LayoutParams(0, dp(50), 1f))
        filters.addView(action("Trier") {
            sortedNewestFirst = !sortedNewestFirst
            updateItems()
        }, LinearLayout.LayoutParams(0, dp(50), 1f))
        layout.addView(filters)
        selectionActions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            visibility = View.GONE
        }.also { actions ->
            actions.addView(action("Classer") { classifySelected() }, LinearLayout.LayoutParams(0, dp(49), 1f))
            actions.addView(action("Partager") { shareSelected() }, LinearLayout.LayoutParams(0, dp(49), 1f))
            actions.addView(action("Annuler") { selected.clear(); updateSelection() }, LinearLayout.LayoutParams(0, dp(49), 1f))
            layout.addView(actions)
        }
        val g = GridView(this).apply {
            numColumns = 3
            horizontalSpacing = dp(5)
            verticalSpacing = dp(5)
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
            setBackgroundColor(black)
            setPadding(0, dp(7), 0, dp(7))
        }
        grid = g
        galleryAdapter = object : BaseAdapter() {
            override fun getCount() = minOf(shownItems.size, visibleLimit)
            override fun getItem(position: Int) = shownItems[position]
            override fun getItemId(position: Int) = shownItems[position].id
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val media = shownItems[position]
                val frame = FrameLayout(this@MainActivity)
                val image = ImageView(this@MainActivity).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    setBackgroundColor(this@MainActivity.background)
                    tag = media.key
                }
                frame.addView(image, FrameLayout.LayoutParams(-1, dp(122)))
                val caption = TextView(this@MainActivity).apply {
                    text = (if (media.mime.startsWith("video/")) "▶ " else "") + media.title
                    textSize = 10f
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setTextColor(cream)
                    setBackgroundColor(0xCC080705.toInt())
                    setPadding(dp(3), dp(3), dp(3), dp(3))
                }
                frame.addView(caption, FrameLayout.LayoutParams(-1, dp(25), Gravity.BOTTOM))
                if (selected.contains(media.key)) {
                    val tick = TextView(this@MainActivity).apply {
                        text = "✓"
                        textSize = 25f
                        setTextColor(gold)
                        setBackgroundColor(0xDD080705.toInt())
                    }
                    frame.addView(tick, FrameLayout.LayoutParams(dp(32), dp(32), Gravity.TOP or Gravity.END))
                }
                val cached = bitmapCache.get(media.key)
                if (cached != null) image.setImageBitmap(cached)
                else io.execute {
                    val bitmap = try { thumbnail(media) } catch (_: Exception) { null }
                    if (bitmap != null) {
                        bitmapCache.put(media.key, bitmap)
                        image.post { if (image.tag == media.key) image.setImageBitmap(bitmap) }
                    }
                }
                return frame
            }
        }
        g.adapter = galleryAdapter
        g.setOnItemClickListener { _, _, position, _ ->
            val media = shownItems[position]
            if (selected.isEmpty()) openMedia(media) else {
                if (!selected.add(media.key)) selected.remove(media.key)
                updateSelection()
            }
        }
        g.setOnItemLongClickListener { _, _, position, _ ->
            val key = shownItems[position].key
            if (!selected.add(key)) selected.remove(key)
            updateSelection()
            true
        }
        layout.addView(g, LinearLayout.LayoutParams(-1, 0, 1f))
        val more = action("Afficher 120 éléments supplémentaires") {
            visibleLimit += 120
            galleryAdapter?.notifyDataSetChanged()
            refreshInfo()
        }
        more.tag = "more"
        layout.addView(more, LinearLayout.LayoutParams(-1, dp(45)))
        setContentView(layout)
        val queriedRoute = route
        io.execute {
            val items = queryMedia(queriedRoute)
            runOnUiThread {
                if (requestNumber != generation || route != queriedRoute) return@runOnUiThread
                galleryItems = items
                updateItems()
            }
        }
    }

    private fun queryMedia(target: Route): List<Media> {
        val types = when (target) {
            Route.IMAGES -> listOf(true)
            Route.VIDEOS -> listOf(false)
            else -> listOf(true, false)
        }
        val result = arrayListOf<Media>()
        for (images in types) {
            val permission = if (Build.VERSION.SDK_INT < 33) Manifest.permission.READ_EXTERNAL_STORAGE
                else if (images) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_MEDIA_VIDEO
            if (!allowed(permission) && !(Build.VERSION.SDK_INT >= 34 && allowed(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED))) continue
            val collection = if (images) MediaStore.Images.Media.EXTERNAL_CONTENT_URI else MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            val columns = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.MIME_TYPE, MediaStore.MediaColumns.SIZE,
                MediaStore.MediaColumns.DATE_ADDED, "bucket_display_name")
            try {
                contentResolver.query(collection, columns, null, null,
                    "${MediaStore.MediaColumns.DATE_ADDED} DESC")?.use { c ->
                    val idIx = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                    val titleIx = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                    val mimeIx = c.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
                    val sizeIx = c.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                    val dateIx = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
                    val albumIx = c.getColumnIndexOrThrow("bucket_display_name")
                    while (c.moveToNext()) {
                        val id = c.getLong(idIx)
                        val mime = c.getString(mimeIx) ?: (if (images) "image/*" else "video/*")
                        result.add(Media(id, android.content.ContentUris.withAppendedId(collection, id), mime,
                            c.getString(titleIx) ?: "Sans titre", c.getString(albumIx) ?: "Autres",
                            c.getLong(dateIx), c.getLong(sizeIx)))
                    }
                }
            } catch (_: SecurityException) { /* Media permissions can be changed in Settings. */ }
            catch (_: Exception) { /* Remain usable if a media provider is unavailable. */ }
        }
        return if (target == Route.UNSORTED) result.filter { getCategory(it).isEmpty() } else result
    }

    private fun thumbnail(media: Media): Bitmap? {
        if (Build.VERSION.SDK_INT >= 29) return contentResolver.loadThumbnail(media.uri, Size(220, 220), null)
        return if (media.mime.startsWith("video/")) {
            @Suppress("DEPRECATION")
            MediaStore.Video.Thumbnails.getThumbnail(contentResolver, media.id,
                MediaStore.Video.Thumbnails.MINI_KIND, null)
        } else {
            @Suppress("DEPRECATION")
            MediaStore.Images.Thumbnails.getThumbnail(contentResolver, media.id,
                MediaStore.Images.Thumbnails.MINI_KIND, null)
        }
    }

    private fun updateItems() {
        val filtered = galleryItems.filter { media ->
            (albumFilter == null || media.album == albumFilter) &&
                (categoryFilter == null || getCategory(media) == categoryFilter) &&
                (route != Route.UNSORTED || getCategory(media).isEmpty())
        }
        shownItems = if (sortedNewestFirst) filtered.sortedByDescending { it.date }
            else filtered.sortedBy { it.title.lowercase() }
        galleryAdapter?.notifyDataSetChanged()
        refreshInfo()
        (grid?.parent as? LinearLayout)?.findViewWithTag<Button>("more")?.visibility =
            if (shownItems.size > visibleLimit) View.VISIBLE else View.GONE
    }

    private fun refreshInfo() {
        val count = minOf(shownItems.size, visibleLimit)
        selectionInfo?.text = if (selected.isEmpty()) "$count / ${shownItems.size} médias · appui long pour sélectionner"
            else "${selected.size} sélectionné(s) · $count / ${shownItems.size} médias"
    }

    private fun updateSelection() {
        selectionActions?.visibility = if (selected.isEmpty()) View.GONE else View.VISIBLE
        galleryAdapter?.notifyDataSetChanged()
        refreshInfo()
    }

    private fun chooseAlbum() {
        val albums = galleryItems.map { it.album }.distinct().sorted()
        val choices = (listOf("Tous les albums") + albums).toTypedArray()
        AlertDialog.Builder(this).setTitle("Albums du téléphone")
            .setItems(choices) { _, index -> albumFilter = if (index == 0) null else albums[index - 1]; updateItems() }
            .show()
    }

    private fun chooseCategory() {
        val categories = listOf("Toutes les catégories", "Sans catégorie") + savedCategories()
        AlertDialog.Builder(this).setTitle("Classement Lapibreizh")
            .setItems(categories.toTypedArray()) { _, index ->
                categoryFilter = when (index) { 0 -> null; 1 -> ""; else -> categories[index] }
                updateItems()
            }.show()
    }

    private fun getCategory(media: Media): String = prefs.getString("media_${media.key}", "") ?: ""

    private fun savedCategories(): List<String> {
        val raw = prefs.getString("category_names", null)
        return if (raw == null) listOf("Académie", "Restaurant", "Sport", "Bricolage", "Voyages")
            else raw.split('\u001f').filter { it.isNotBlank() }
    }

    private fun classifySelected() {
        val cats = savedCategories()
        val choices = (listOf("Sans catégorie") + cats).toTypedArray()
        AlertDialog.Builder(this).setTitle("Classer ${selected.size} élément(s)")
            .setItems(choices) { _, index ->
                val category = if (index == 0) "" else cats[index - 1]
                val edit = prefs.edit()
                selected.forEach { edit.putString("media_$it", category) }
                edit.apply()
                selected.clear()
                if (route == Route.UNSORTED) galleryItems = galleryItems.filter { getCategory(it).isEmpty() }
                updateSelection()
                updateItems()
                Toast.makeText(this, "Classement enregistré sans déplacer les fichiers", Toast.LENGTH_LONG).show()
            }.show()
    }

    private fun openMedia(media: Media) {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(media.uri, media.mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try { startActivity(intent) } catch (_: Exception) {
            Toast.makeText(this, "Aucune application disponible pour ouvrir ce média", Toast.LENGTH_LONG).show()
        }
    }

    private fun shareSelected() {
        val media = galleryItems.filter { selected.contains(it.key) }
        if (media.isEmpty()) return
        val intent = Intent(if (media.size == 1) Intent.ACTION_SEND else Intent.ACTION_SEND_MULTIPLE)
        intent.type = when {
            media.all { it.mime.startsWith("image/") } -> "image/*"
            media.all { it.mime.startsWith("video/") } -> "video/*"
            else -> "*/*"
        }
        val uris = ArrayList(media.map { it.uri })
        if (media.size == 1) intent.putExtra(Intent.EXTRA_STREAM, uris.first())
        else intent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        val clips = ClipData.newUri(contentResolver, "Médias Lapibreizh", uris.first())
        uris.drop(1).forEach { clips.addItem(ClipData.Item(it)) }
        intent.clipData = clips
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try { startActivity(Intent.createChooser(intent, "Partager les médias")) }
        catch (_: Exception) { Toast.makeText(this, "Partage indisponible", Toast.LENGTH_SHORT).show() }
    }

    private fun showSettings() {
        route = Route.SETTINGS
        selected.clear()
        val layout = root()
        layout.addView(action("‹ Retour à l’accueil") { showHome() })
        layout.addView(heading("PARAMÈTRES"))
        layout.addView(note("Catégories internes : les photos et vidéos d’origine ne sont jamais déplacées ou supprimées."))
        layout.addView(heading("Mes catégories", 19f))
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        savedCategories().forEach { category -> list.addView(note("• $category")) }
        layout.addView(list)
        layout.addView(action("Ajouter une catégorie") {
            val editText = EditText(this).apply {
                hint = "Nom de la catégorie"
                setTextColor(cream)
                setHintTextColor(0xFFAAAAAA.toInt())
                setSingleLine(true)
            }
            AlertDialog.Builder(this).setTitle("Nouvelle catégorie").setView(editText)
                .setPositiveButton("Ajouter") { _, _ ->
                    val name = editText.text.toString().trim().replace("\u001f", "")
                    if (name.isNotEmpty() && name.length <= 40 && !savedCategories().any { it.equals(name, true) }) {
                        prefs.edit().putString("category_names", (savedCategories() + name).joinToString("\u001f")).apply()
                        showSettings()
                    } else Toast.makeText(this, "Nom vide, trop long ou déjà utilisé", Toast.LENGTH_SHORT).show()
                }.setNegativeButton("Annuler", null).show()
        })
        layout.addView(note("Pour attribuer une catégorie : ouvre Mes images ou Montages vidéo, puis maintiens une miniature appuyée."))
        layout.addView(note("Cette version n’analyse pas automatiquement les images. Aucun doublon n’est effacé."))
        setContentView(ScrollView(this).apply { addView(layout) })
    }

    @Deprecated("Used to support navigation on Android 8-12")
    override fun onBackPressed() {
        if (route == Route.HOME) super.onBackPressed() else showHome()
    }

    override fun onDestroy() {
        generation++
        io.shutdownNow()
        super.onDestroy()
    }
}
