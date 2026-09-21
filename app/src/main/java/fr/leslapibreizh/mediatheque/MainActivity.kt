package fr.leslapibreizh.mediatheque

import android.Manifest
import android.app.AlertDialog
import android.content.ClipData
import android.content.Intent
import android.provider.DocumentsContract
import android.text.Editable
import android.text.TextWatcher
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.security.MessageDigest
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
 * V0.4 : évolution de la V0.3 avec habillage des maquettes validées, tri explicite, favoris,
 * recherche de photos strictement identiques et mise à la corbeille avec accord système.
 * Une copie n'efface JAMAIS l'original ; une mise à la corbeille exige deux confirmations.
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
    private var sortMode = 0 // Date ↓, Date ↑, Nom A→Z, Nom Z→A, Taille ↓, Type
    private var dashboardVisible = false
    private var sortButton: Button? = null
    private var visibleLimit = 120
    private var grid: GridView? = null
    private var galleryAdapter: BaseAdapter? = null
    private var selectionActions: View? = null
    private var selectionInfo: TextView? = null
    private var duplicateButton: Button? = null
    private var generation = 0
    private var searchQuery = ""
    private var screensOnly = false
    private var duplicatesOnly = false
    private var duplicateKeys = emptySet<String>()
    private var pendingCopy = emptyList<Media>()
    private var pendingTrash = emptyList<Media>()
    private val backupExportRequest = 301
    private val backupImportRequest = 302
    private val copyFolderRequest = 303
    private val trashRequest = 304

    private enum class Route { HOME, IMAGES, VIDEOS, UNSORTED, FAVORITES, SETTINGS }
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
        setPadding(dp(9), dp(6), dp(9), dp(5))
    }

    private fun heading(text: String, size: Float = 23f) = TextView(this).apply {
        this.text = text
        textSize = size
        typeface = android.graphics.Typeface.create("serif", android.graphics.Typeface.BOLD)
        setTextColor(gold)
        gravity = Gravity.CENTER
        setPadding(dp(4), dp(7), dp(4), dp(8))
    }

    private fun note(text: String) = TextView(this).apply {
        this.text = text
        textSize = 13f
        setTextColor(cream)
        gravity = Gravity.CENTER
        setPadding(dp(4), dp(5), dp(4), dp(6))
    }

    private fun shape(fill: Int = background, outline: Int = gold, radius: Int = 13) =
        GradientDrawable().apply {
            setColor(fill)
            cornerRadius = dp(radius).toFloat()
            setStroke(dp(1), outline)
        }

    private fun action(text: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        textSize = 12.5f
        isAllCaps = false
        setTextColor(cream)
        backgroundTintList = null
        setBackground(shape())
        setPadding(dp(5), dp(3), dp(5), dp(3))
        minHeight = dp(43)
        setOnClickListener { onClick() }
    }

    private fun dp(n: Int) = (resources.displayMetrics.density * n + 0.5f).toInt()

    private fun hero(drawable: Int, height: Int): ImageView = ImageView(this).apply {
        setImageResource(drawable)
        scaleType = ImageView.ScaleType.FIT_XY
        adjustViewBounds = false
        contentDescription = "Illustration officielle Les Lapibreizh, deux lapins et phare breton"
        layoutParams = LinearLayout.LayoutParams(-1, dp(height))
    }

    private fun navBar(active: Route): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        val items = listOf(
            Triple("⌂ Accueil", Route.HOME, 0),
            Triple("▧ Images", Route.IMAGES, 0),
            Triple("▶ Vidéos", Route.VIDEOS, 0),
            Triple("♥ Favoris", Route.FAVORITES, 0),
            Triple("⚙ Réglages", Route.SETTINGS, 0)
        )
        items.forEach { (label, target, _) ->
            val button = action(label) {
                when(target) {
                    Route.HOME -> showHome()
                    Route.SETTINGS -> showSettings()
                    else -> navigate(target)
                }
            }.apply {
                textSize = 10f
                if (active == target) {
                    setBackground(shape(Color.rgb(85, 62, 26), gold, 10))
                    setTextColor(Color.WHITE)
                }
            }
            addView(button, LinearLayout.LayoutParams(0, dp(49), 1f).apply {
                marginStart=dp(1); marginEnd=dp(1)
            })
        }
    }

    private fun homeTile(art: Int, title: String, detail: String, target: Route): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackground(shape(Color.rgb(26, 23, 19), gold, 12))
            val image = ImageView(this@MainActivity).apply {
                setImageResource(art)
                scaleType = ImageView.ScaleType.CENTER_CROP
                contentDescription = title
            }
            addView(image, LinearLayout.LayoutParams(-1, dp(83)))
            addView(heading(title, 16f))
            addView(note(detail))
            setOnClickListener { navigate(target) }
            isClickable = true
            isFocusable = true
            contentDescription = "$title. $detail"
        }

    private fun showHome() {
        route = Route.HOME
        dashboardVisible = false
        selected.clear()
        val screen = root()
        val content = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
        content.addView(hero(R.drawable.hero_home, 279))
        val pair = LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL }
        pair.addView(homeTile(R.drawable.tile_academie, "IMAGES", "Trier • Retrouver • Partager", Route.IMAGES),
            LinearLayout.LayoutParams(0, dp(161), 1f).apply { marginEnd=dp(3) })
        pair.addView(homeTile(R.drawable.tile_episodes_video, "MONTAGES VIDÉO", "Trier • Retrouver • Partager", Route.VIDEOS),
            LinearLayout.LayoutParams(0, dp(161), 1f).apply { marginStart=dp(3) })
        content.addView(pair)
        val quick = LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL }
        listOf(
            "⌕ Recherche" to Route.IMAGES,
            "▤ À classer" to Route.UNSORTED,
            "♥ Favoris" to Route.FAVORITES,
            "⚙ Paramètres" to Route.SETTINGS
        ).forEach { (label,target) ->
            quick.addView(action(label) {
                if (label.contains("Recherche") && hasPermission(Route.IMAGES)) showGallery(Route.IMAGES)
                else navigate(target)
            },LinearLayout.LayoutParams(0,dp(57),1f).apply {
                marginStart=dp(2);marginEnd=dp(2)
            })
        }
        content.addView(quick, LinearLayout.LayoutParams(-1, dp(60)).apply { topMargin=dp(7) })
        content.addView(note("Créer • Classer • Partager • Revivre"))
        screen.addView(ScrollView(this).apply { fillViewport=false; addView(content) },
            LinearLayout.LayoutParams(-1,0,1f))
        screen.addView(navBar(Route.HOME))
        setContentView(screen)
    }

    private fun navigate(next: Route) {
        if (next == Route.SETTINGS) { showSettings(); return }
        if (next == Route.HOME) { showHome(); return }
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
        if (next == Route.IMAGES || next == Route.VIDEOS) showCategoryDashboard(next)
        else showGallery(next)
    }

    private fun hasPermission(target: Route): Boolean {
        if (Build.VERSION.SDK_INT < 33) return allowed(Manifest.permission.READ_EXTERNAL_STORAGE)
        val selectedAllowed = Build.VERSION.SDK_INT >= 34 && allowed(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        return when (target) {
            Route.IMAGES -> allowed(Manifest.permission.READ_MEDIA_IMAGES) || selectedAllowed
            Route.VIDEOS -> allowed(Manifest.permission.READ_MEDIA_VIDEO) || selectedAllowed
            Route.UNSORTED, Route.FAVORITES -> allowed(Manifest.permission.READ_MEDIA_IMAGES) ||
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
            if (hasPermission(route)) navigate(route)
            else AlertDialog.Builder(this).setTitle("Accès aux médias")
                .setMessage("Autorise les photos ou vidéos pour afficher ta bibliothèque. Aucun fichier ne sera modifié.")
                .setPositiveButton("Compris") { _, _ -> showHome() }
                .show()
        }
    }

    private fun categoryArtwork(name: String, video: Boolean): Int {
        val key = name.lowercase()
        if (video) return when {
            "vert" in key -> R.drawable.tile_vert
            "bleu" in key -> R.drawable.tile_bleu
            "orange" in key -> R.drawable.tile_orange
            "rouge" in key -> R.drawable.tile_rouge
            "violet" in key -> R.drawable.tile_violet
            "bonus" in key -> R.drawable.tile_bonus
            "cueillette" in key -> R.drawable.tile_cueillette
            "restaurant" in key -> R.drawable.tile_restaurant_video
            "voyage" in key -> R.drawable.tile_voyages_video
            "bricol" in key -> R.drawable.tile_bricolage_video
            "sport" in key -> R.drawable.tile_sport_video
            "acad" in key -> R.drawable.tile_academie
            "couleur" in key || "fiche" in key -> R.drawable.tile_couleurs
            "personnage" in key -> R.drawable.tile_personnages
            "réel" in key || "reel" in key || "lapin" in key -> R.drawable.tile_lapins
            else -> R.drawable.lapibreizh_icon
        }
        return when {
            "acad" in key -> R.drawable.tile_academie
            "couleur" in key || "fiche" in key -> R.drawable.tile_couleurs
            "sport" in key -> R.drawable.tile_sport
            "bricol" in key -> R.drawable.tile_bricolage
            "restaurant" in key -> R.drawable.tile_restaurant
            "voyage" in key -> R.drawable.tile_voyages
            "épisode" in key || "episode" in key -> R.drawable.tile_episodes
            "personnage" in key -> R.drawable.tile_personnages
            "réel" in key || "reel" in key || "lapin" in key -> R.drawable.tile_lapins
            else -> R.drawable.tile_aclasser
        }
    }

    /** An actual category browser: media counts are computed from MediaStore, never baked into artwork. */
    private fun showCategoryDashboard(target: Route) {
        route=target
        dashboardVisible = true
        categoryFilter = null
        albumFilter = null
        selected.clear()
        generation++
        val request=generation
        val screen=root()
        val content=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
        content.addView(action("‹ Retour à l’accueil") { showHome() })
        content.addView(hero(if (target==Route.VIDEOS) R.drawable.hero_videos else R.drawable.hero_images, 110))
        content.addView(heading(if (target==Route.VIDEOS) "MES MONTAGES VIDÉO" else "MES IMAGES",19f))
        content.addView(action("▧ Ouvrir toute la galerie") { showGallery(target) },
            LinearLayout.LayoutParams(-1,dp(51)).apply { bottomMargin=dp(6) })
        val status=note("Chargement des catégories et du nombre réel de médias…")
        content.addView(status)
        val gallery=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
        content.addView(gallery)
        content.addView(action("+ Gérer et créer mes catégories") { showSettings() },
            LinearLayout.LayoutParams(-1,dp(51)).apply { topMargin=dp(8) })
        screen.addView(ScrollView(this).apply { addView(content) }, LinearLayout.LayoutParams(-1,0,1f))
        screen.addView(navBar(target))
        setContentView(screen)
        io.execute {
            val items=queryMedia(target)
            runOnUiThread {
                if (generation != request || route != target) return@runOnUiThread
                galleryItems=items
                status.text="${items.size} média(s) accessibles • catégories de l’application"
                val categories=savedCategories()+"Sans catégorie"
                val cols=if (resources.configuration.screenWidthDp >= 600) 3 else 2
                categories.chunked(cols).forEach { line ->
                    val row=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL }
                    line.forEach { name ->
                        val count=items.count { if(name=="Sans catégorie") getCategory(it).isEmpty() else getCategory(it)==name }
                        val tile=LinearLayout(this).apply {
                            orientation=LinearLayout.VERTICAL
                            setBackground(shape(Color.rgb(27,24,20),gold,12))
                            val visual=ImageView(this@MainActivity).apply {
                                setImageResource(categoryArtwork(name,target==Route.VIDEOS))
                                scaleType=ImageView.ScaleType.CENTER_CROP
                                contentDescription="Illustration $name"
                            }
                            addView(visual,LinearLayout.LayoutParams(-1,dp(85)))
                            addView(heading(name,14f))
                            addView(note("$count média(s) ›"))
                            setOnClickListener {
                                categoryFilter=if(name=="Sans catégorie") "" else name
                                selected.clear();searchQuery="";screensOnly=false;duplicatesOnly=false
                                visibleLimit=120
                                loadGallery()
                            }
                            isClickable=true;isFocusable=true
                        }
                        row.addView(tile,LinearLayout.LayoutParams(0,dp(155),1f).apply {
                            marginStart=dp(3);marginEnd=dp(3);topMargin=dp(5)
                        })
                    }
                    if(line.size<cols) repeat(cols-line.size) { row.addView(View(this),LinearLayout.LayoutParams(0,dp(155),1f)) }
                    gallery.addView(row)
                }
            }
        }
    }

    private fun showGallery(next: Route) {
        dashboardVisible = false
        route = next
        selected.clear()
        albumFilter = null
        categoryFilter = null
        searchQuery = ""
        screensOnly = false
        duplicatesOnly = false
        duplicateKeys = emptySet()
        visibleLimit = 120
        loadGallery()
    }

    private fun loadGallery() {
        dashboardVisible = false
        val requestNumber = ++generation
        val layout = root()
        val title = when (route) {
            Route.IMAGES -> "MES IMAGES"
            Route.VIDEOS -> "MONTAGES VIDÉO — EDITS"
            Route.FAVORITES -> "MES FAVORIS"
            else -> "À CLASSER"
        }
        val toolbar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        toolbar.addView(action("‹ Retour") {
            if (route==Route.IMAGES || route==Route.VIDEOS) showCategoryDashboard(route)
            else showHome()
        }, LinearLayout.LayoutParams(dp(100), dp(48)))
        toolbar.addView(heading(title, 18f), LinearLayout.LayoutParams(0, dp(48), 1f))
        layout.addView(toolbar)
        layout.addView(hero(if (route==Route.VIDEOS) R.drawable.hero_videos else R.drawable.hero_images, 101))
        if (categoryFilter != null) layout.addView(heading(if(categoryFilter=="") "Sans catégorie" else categoryFilter!!, 16f))
        val info = note("Chargement des médias…")
        selectionInfo = info
        layout.addView(info)
        val filters = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        filters.addView(action("Albums") { chooseAlbum() }, LinearLayout.LayoutParams(0, dp(50), 1f))
        filters.addView(action("Catégories") { chooseCategory() }, LinearLayout.LayoutParams(0, dp(50), 1f))
        val currentSortButton = action(sortShortLabel()) { chooseSort() }
        sortButton = currentSortButton
        filters.addView(currentSortButton, LinearLayout.LayoutParams(0, dp(50), 1f))
        layout.addView(filters)
        val search = EditText(this).apply {
            hint = "Rechercher un nom, un album…"
            setSingleLine(true)
            textSize = 15f
            setTextColor(cream)
            setHintTextColor(0xFFAAAAAA.toInt())
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    searchQuery = s?.toString()?.trim()?.lowercase() ?: ""
                    clearSelectionForFilter()
                    updateItems()
                }
                override fun afterTextChanged(s: Editable?) {}
            })
        }
        layout.addView(search, LinearLayout.LayoutParams(-1, dp(45)))
        val tools = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val screenButton = action("Captures") { }
        screenButton.setOnClickListener {
            screensOnly = !screensOnly
            screenButton.text = if (screensOnly) "Captures ✓" else "Captures"
            clearSelectionForFilter()
            updateItems()
        }
        tools.addView(screenButton, LinearLayout.LayoutParams(0, dp(45), 1f))
        duplicateButton = action("Doublons") { scanExactDuplicates() }
        tools.addView(duplicateButton, LinearLayout.LayoutParams(0, dp(45), 1f))
        tools.addView(action("Sélection") { chooseSelection() }, LinearLayout.LayoutParams(0, dp(45), 1f))
        layout.addView(tools)
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf(
            "Classer" to { classifySelected() },
            "Copier" to { copySelected() },
            "Corbeille" to { trashSelected() },
            "♥ Favoris" to { toggleFavorites() },
            "Partager" to { shareSelected() },
            "Annuler" to { selected.clear(); updateSelection() }
        ).forEach { (name, operation) ->
            actions.addView(action(name, operation), LinearLayout.LayoutParams(dp(108), dp(49)))
        }
        val actionScroller = HorizontalScrollView(this).apply {
            visibility = View.GONE
            addView(actions)
        }
        selectionActions = actionScroller
        layout.addView(actionScroller, LinearLayout.LayoutParams(-1, dp(51)))
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
        val screen = root()
        screen.addView(layout, LinearLayout.LayoutParams(-1,0,1f))
        screen.addView(navBar(route))
        setContentView(screen)
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
                (route != Route.UNSORTED || getCategory(media).isEmpty()) &&
                (route != Route.FAVORITES || prefs.getBoolean("fav_${media.key}", false)) &&
                (searchQuery.isEmpty() || media.title.lowercase().contains(searchQuery) ||
                    media.album.lowercase().contains(searchQuery)) &&
                (!screensOnly || isScreenshot(media)) &&
                (!duplicatesOnly || duplicateKeys.contains(media.key))
        }
        shownItems = when(sortMode) {
            0 -> filtered.sortedByDescending { it.date }
            1 -> filtered.sortedBy { it.date }
            2 -> filtered.sortedBy { it.title.lowercase() }
            3 -> filtered.sortedByDescending { it.title.lowercase() }
            4 -> filtered.sortedByDescending { it.size }
            else -> filtered.sortedWith(compareBy<Media> { it.mime }.thenBy { it.title.lowercase() })
        }
        galleryAdapter?.notifyDataSetChanged()
        refreshInfo()
        // Keep the labels truthful even when filters are combined.
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

    private fun sortShortLabel() = arrayOf("Date ↓ ▾", "Date ↑ ▾", "Nom A-Z ▾", "Nom Z-A ▾", "Taille ↓ ▾", "Type ▾")[sortMode]

    private fun chooseSort() {
        val labels=arrayOf("Date : plus récent d’abord", "Date : plus ancien d’abord",
            "Nom : A → Z", "Nom : Z → A", "Taille : plus grand d’abord", "Type de fichier puis nom")
        AlertDialog.Builder(this).setTitle("Trier les médias")
            .setSingleChoiceItems(labels,sortMode) { dialog,choice ->
                sortMode=choice
                sortButton?.text=sortShortLabel()
                clearSelectionForFilter()
                updateItems()
                dialog.dismiss()
                toast("Tri appliqué : ${labels[choice]}")
            }.setNegativeButton("Annuler",null).show()
    }

    private fun toggleFavorites() {
        if(selected.isEmpty()) return
        val items=galleryItems.filter { selected.contains(it.key) }
        val allFavorite=items.all { prefs.getBoolean("fav_${it.key}",false) }
        val edit=prefs.edit()
        items.forEach { edit.putBoolean("fav_${it.key}", !allFavorite) }
        edit.apply()
        selected.clear()
        updateSelection()
        updateItems()
        toast(if(allFavorite) "${items.size} média(s) retiré(s) des favoris." else "${items.size} média(s) ajouté(s) aux favoris.")
    }

    private fun chooseAlbum() {
        val albums = galleryItems.map { it.album }.distinct().sorted()
        val choices = (listOf("Tous les albums") + albums).toTypedArray()
        AlertDialog.Builder(this).setTitle("Albums du téléphone")
            .setItems(choices) { _, index ->
                albumFilter = if (index == 0) null else albums[index - 1]
                clearSelectionForFilter()
                updateItems()
            }
            .show()
    }

    private fun chooseCategory() {
        val categories = listOf("Toutes les catégories", "Sans catégorie") + savedCategories() + "+ Gérer les catégories"
        AlertDialog.Builder(this).setTitle("Classement Lapibreizh")
            .setItems(categories.toTypedArray()) { _, index ->
                if (index == categories.lastIndex) { showSettings(); return@setItems }
                categoryFilter = when (index) { 0 -> null; 1 -> ""; else -> categories[index] }
                clearSelectionForFilter()
                updateItems()
            }.show()
    }

    private fun getCategory(media: Media): String = prefs.getString("media_${media.key}", "") ?: ""

    private fun savedCategories(): List<String> {
        val raw = prefs.getString("category_names", null)
        return if (raw == null) listOf("Académie", "Fiches couleurs", "Sport", "Bricolage", "Restaurant", "Voyages", "Épisodes", "Personnages", "Lapins réels", "Cueillette")
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
        if (media.size > 200) {
            toast("Pour partager, sélectionne au maximum 200 médias à la fois.")
            return
        }
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

    private fun clearSelectionForFilter() {
        if (selected.isNotEmpty()) { selected.clear(); updateSelection() }
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    private fun isScreenshot(media: Media): Boolean {
        val location = (media.album + " " + media.title).lowercase()
        return listOf("screenshot", "screen shot", "capture", "screencap").any { location.contains(it) }
    }

    private fun chooseSelection() {
        if (shownItems.isEmpty()) { toast("Aucun média à sélectionner."); return }
        val visibleCount = minOf(shownItems.size, visibleLimit)
        AlertDialog.Builder(this).setTitle("Sélection multiple")
            .setItems(arrayOf("Sélectionner les $visibleCount affichés", "Sélectionner les ${shownItems.size} résultats filtrés", "Tout désélectionner")) { _, choice ->
                when (choice) {
                    0 -> { selected.addAll(shownItems.take(visibleCount).map { it.key }); updateSelection() }
                    1 -> AlertDialog.Builder(this).setTitle("Sélectionner ${shownItems.size} médias ?")
                        .setMessage("La sélection concerne uniquement les résultats du filtre actuel. Toute mise à la corbeille demandera une confirmation séparée.")
                        .setNegativeButton("Annuler", null)
                        .setPositiveButton("Sélectionner") { _, _ ->
                            selected.addAll(shownItems.map { it.key }); updateSelection()
                        }.show()
                    else -> { selected.clear(); updateSelection() }
                }
            }.show()
    }

    /** Only exact byte-for-byte IMAGE matches, identified by size then SHA-256. */
    private fun scanExactDuplicates() {
        if (duplicatesOnly) {
            duplicatesOnly = false
            duplicateButton?.text = "Doublons"
            clearSelectionForFilter()
            updateItems()
            return
        }
        if (galleryItems.isEmpty()) { toast("Ouvre d'abord une galerie contenant des photos."); return }
        val request = generation
        val images = galleryItems.filter { it.mime.startsWith("image/") && it.size in 1..50_000_000L }
        toast("Recherche des photos strictement identiques en cours…")
        duplicateButton?.isEnabled = false
        io.execute {
            val matches = mutableSetOf<String>()
            val candidates = images.groupBy { it.size }.values.filter { it.size > 1 }
            for (bucket in candidates) {
                if (Thread.currentThread().isInterrupted) break
                val byHash = mutableMapOf<String, MutableList<Media>>()
                for (media in bucket) {
                    val signature = try { sha256(media.uri) } catch (_: Exception) { null }
                    if (signature != null) byHash.getOrPut(signature) { mutableListOf() }.add(media)
                }
                byHash.values.filter { it.size > 1 }.forEach { group ->
                    group.forEach { matches.add(it.key) }
                }
            }
            runOnUiThread {
                if (request != generation || route == Route.HOME || route == Route.SETTINGS) return@runOnUiThread
                duplicateButton?.isEnabled = true
                duplicateKeys = matches
                duplicatesOnly = true
                duplicateButton?.text = "Doublons ✓"
                clearSelectionForFilter()
                updateItems()
                toast("${matches.size} photos dans des groupes de doublons exacts (50 Mo maximum par photo). Aucun fichier supprimé.")
            }
        }
    }

    private fun sha256(uri: Uri): String? {
        val digest = MessageDigest.getInstance("SHA-256")
        val stream = contentResolver.openInputStream(uri) ?: return null
        stream.use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val size = input.read(buffer)
                if (size < 0) break
                if (Thread.currentThread().isInterrupted) throw IOException("Interrompu")
                if (size > 0) digest.update(buffer, 0, size)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun copySelected() {
        val files = galleryItems.filter { selected.contains(it.key) }
        if (files.isEmpty()) return
        if (files.size > 250) { toast("Copie limitée à 250 médias par opération."); return }
        AlertDialog.Builder(this).setTitle("Copier ${files.size} médias")
            .setMessage("Choisis ensuite un dossier de destination. Les fichiers d'origine ne seront NI déplacés NI effacés. Vérifie tes copies avant toute mise à la corbeille.")
            .setNegativeButton("Annuler", null)
            .setPositiveButton("Choisir le dossier") { _, _ ->
                pendingCopy = files
                try {
                    startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                    }, copyFolderRequest)
                } catch (_: Exception) { pendingCopy = emptyList(); toast("Sélecteur de dossier indisponible.") }
            }.show()
    }

    private fun copyToFolder(tree: Uri, files: List<Media>) {
        var copied = 0
        var failed = 0
        val folder = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        files.forEachIndexed { index, media ->
            var created: Uri? = null
            try {
                val safeName = media.title.replace(Regex("[^\\p{L}\\p{N}._ -]"), "_").takeLast(85)
                val name = "Lapibreizh_${System.currentTimeMillis()}_${index + 1}_${safeName.ifBlank { "media" }}"
                created = DocumentsContract.createDocument(contentResolver, folder, media.mime, name)
                    ?: throw IOException("Impossible de créer le document")
                val target = created
                val bytes = (contentResolver.openInputStream(media.uri) ?: throw IOException("Source illisible")).use { input ->
                    (contentResolver.openOutputStream(target, "w") ?: throw IOException("Destination non accessible")).use { output ->
                        input.copyTo(output, 64 * 1024)
                    }
                }
                if (media.size > 0 && bytes != media.size) throw IOException("Taille copiée incorrecte")
                copied++
            } catch (_: Exception) {
                failed++
                if (created != null) try { DocumentsContract.deleteDocument(contentResolver, created) } catch (_: Exception) {}
            }
        }
        runOnUiThread {
            AlertDialog.Builder(this).setTitle("Copie terminée")
                .setMessage("$copied copie(s) effectuée(s), $failed échec(s). Les originaux n'ont pas été déplacés ou effacés. Vérifie les fichiers dans le dossier choisi.")
                .setPositiveButton("Compris", null).show()
        }
    }

    private fun trashSelected() {
        val files = galleryItems.filter { selected.contains(it.key) }
        if (files.isEmpty()) return
        if (Build.VERSION.SDK_INT < 30) {
            toast("Corbeille disponible sur Android 11 ou supérieur uniquement.")
            return
        }
        if (files.size > 100) {
            toast("Par sécurité, la corbeille est limitée à 100 médias par opération.")
            return
        }
        AlertDialog.Builder(this).setTitle("Mettre ${files.size} médias à la corbeille ?")
            .setMessage("Cette opération agit sur les VRAIS fichiers du téléphone, pas seulement sur leur catégorie. Android demandera une seconde confirmation. Tu pourras généralement récupérer les fichiers depuis la corbeille avant son expiration. Ce n'est PAS un effacement sécurisé.")
            .setNegativeButton("Conserver", null)
            .setPositiveButton("Continuer vers Android") { _, _ ->
                pendingTrash = files
                try {
                    val intent = MediaStore.createTrashRequest(contentResolver, files.map { it.uri }, true)
                    startIntentSenderForResult(intent.intentSender, trashRequest, null, 0, 0, 0)
                } catch (_: Exception) {
                    pendingTrash = emptyList()
                    toast("La demande de mise à la corbeille a échoué : aucun fichier modifié par l'application.")
                }
            }.show()
    }

    private fun exportCategories() {
        try {
            startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "application/json"
                putExtra(Intent.EXTRA_TITLE, "Lapibreizh-classement.json")
            }, backupExportRequest)
        } catch (_: Exception) { toast("Enregistrement de la sauvegarde indisponible.") }
    }

    private fun exportJson(): String {
        val assignments = JSONObject()
        val favorites = JSONArray()
        prefs.all.forEach { (key, value) ->
            if (key.startsWith("fav_") && value == true) favorites.put(key.removePrefix("fav_"))
        }
        prefs.all.forEach { (key, value) ->
            if (key.startsWith("media_") && value is String) assignments.put(key.removePrefix("media_"), value)
        }
        return JSONObject().put("format", "lapibreizh-classement")
            .put("version", 1).put("categories", JSONArray(savedCategories()))
            .put("assignments", assignments).put("favorites", favorites).toString(2)
    }

    private fun importCategories() {
        try {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "application/json"
            }, backupImportRequest)
        } catch (_: Exception) { toast("Ouverture de sauvegarde indisponible.") }
    }

    private fun restoreJson(input: String) {
        try {
            val backup = JSONObject(input)
            if (backup.optString("format") != "lapibreizh-classement" || backup.optInt("version") != 1) {
                toast("Fichier non reconnu : sauvegarde Lapibreizh version 1 requise.")
                return
            }
            val array = backup.getJSONArray("categories")
            if (array.length() > 1000) throw IOException("Trop de catégories")
            val imported = (0 until array.length()).map { array.getString(it) }
                .filter { it.isNotBlank() && it.length <= 40 && !it.contains('\u001f') }
                .distinct()
            val assignments = backup.getJSONObject("assignments")
            val clean = linkedMapOf<String, String>()
            val names = (savedCategories() + imported).distinct().toMutableList()
            val iterator = assignments.keys()
            while (iterator.hasNext()) {
                val key = iterator.next()
                if (!key.matches(Regex("[iv]:[0-9]{1,19}"))) continue
                val category = assignments.optString(key, "")
                if (category.isNotBlank() && (category.length > 40 || category.contains('\u001f'))) continue
                if (category.isNotBlank() && !names.contains(category)) names.add(category)
                clean[key] = category
            }
            AlertDialog.Builder(this).setTitle("Importer le classement ?")
                .setMessage("${clean.size} attribution(s) dans la sauvegarde. Les catégories seront fusionnées. Le classement de ces mêmes médias sera remplacé. Les fichiers du téléphone ne seront jamais touchés. Les identifiants des médias doivent encore correspondre à ceux du téléphone.")
                .setNegativeButton("Annuler", null)
                .setPositiveButton("Importer") { _, _ ->
                    val edit = prefs.edit().putString("category_names", names.joinToString("\u001f"))
                    clean.forEach { (key, value) -> edit.putString("media_$key", value) }
                    val fav = backup.optJSONArray("favorites")
                    if (fav != null && fav.length() <= 100000) {
                        for (i in 0 until fav.length()) {
                            val key = fav.optString(i, "")
                            if (key.matches(Regex("[iv]:[0-9]{1,19}"))) edit.putBoolean("fav_$key", true)
                        }
                    }
                    edit.apply()
                    toast("${clean.size} attribution(s) importée(s). Vérifie les catégories.")
                    showSettings()
                }.show()
        } catch (_: Exception) { toast("Sauvegarde illisible ou incompatible. Aucun classement modifié.") }
    }

    private fun readSmallBackup(uri: Uri): String {
        val source = contentResolver.openInputStream(uri) ?: throw IOException("Fichier inaccessible")
        return source.use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(4096)
            var total = 0
            while (true) {
                val read = input.read(buffer)
                if (read == -1) break
                total += read
                if (total > 8_000_000) throw IOException("Fichier trop volumineux")
                output.write(buffer, 0, read)
            }
            output.toString("UTF-8")
        }
    }

    @Deprecated("Activity result compatibility on Android 8+")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == trashRequest) {
            pendingTrash = emptyList()
            if (resultCode == RESULT_OK) {
                toast("Mise à la corbeille confirmée par Android.")
                if (route != Route.HOME && route != Route.SETTINGS) showGallery(route)
            } else toast("Mise à la corbeille annulée.")
            return
        }
        if (resultCode != RESULT_OK || data?.data == null) {
            if (requestCode == copyFolderRequest) pendingCopy = emptyList()
            return
        }
        val uri = data.data ?: return
        when (requestCode) {
            backupExportRequest -> io.execute {
                val success = try {
                    (contentResolver.openOutputStream(uri, "wt") ?: throw IOException("Écriture impossible"))
                        .bufferedWriter().use { it.write(exportJson()) }
                    true
                } catch (_: Exception) { false }
                runOnUiThread { toast(if (success) "Sauvegarde du classement enregistrée." else "Échec de la sauvegarde : vérifie le fichier.") }
            }
            backupImportRequest -> io.execute {
                val text = try { readSmallBackup(uri) } catch (_: Exception) { null }
                runOnUiThread {
                    if (text == null) toast("Impossible de lire cette sauvegarde (8 Mo maximum).")
                    else restoreJson(text)
                }
            }
            copyFolderRequest -> {
                val batch = pendingCopy
                pendingCopy = emptyList()
                if (batch.isNotEmpty()) {
                    toast("Copie de ${batch.size} média(s) en cours. Garde l'application ouverte.")
                    io.execute { copyToFolder(uri, batch) }
                }
            }
        }
    }

    private fun manageCategory(category: String) {
        AlertDialog.Builder(this).setTitle(category)
            .setItems(arrayOf("Renommer", "Supprimer cette catégorie du classement")) { _, choice ->
                if (choice == 0) {
                    val nameField = EditText(this).apply {
                        setText(category); setSingleLine(true); setTextColor(cream)
                    }
                    AlertDialog.Builder(this).setTitle("Renommer la catégorie").setView(nameField)
                        .setNegativeButton("Annuler", null)
                        .setPositiveButton("Renommer") { _, _ ->
                            val name = nameField.text.toString().trim().replace("\u001f", "")
                            if (name.isEmpty() || name.length > 40 ||
                                savedCategories().any { it.equals(name, true) && it != category }) {
                                toast("Nom vide, trop long ou déjà utilisé.")
                            } else {
                                val edit = prefs.edit()
                                edit.putString("category_names", savedCategories().map { if (it == category) name else it }.joinToString("\u001f"))
                                prefs.all.forEach { (key, value) ->
                                    if (key.startsWith("media_") && value == category) edit.putString(key, name)
                                }
                                edit.apply()
                                showSettings()
                            }
                        }.show()
                } else {
                    val count = prefs.all.count { (key, value) -> key.startsWith("media_") && value == category }
                    AlertDialog.Builder(this).setTitle("Supprimer « $category » ?")
                        .setMessage("$count classement(s) seront retirés : leurs médias redeviendront « Sans catégorie ». AUCUNE photo ou vidéo ne sera supprimée du téléphone.")
                        .setNegativeButton("Annuler", null)
                        .setPositiveButton("Supprimer la catégorie") { _, _ ->
                            val edit = prefs.edit().putString("category_names", savedCategories().filter { it != category }.joinToString("\u001f"))
                            prefs.all.forEach { (key, value) ->
                                if (key.startsWith("media_") && value == category) edit.putString(key, "")
                            }
                            edit.apply()
                            showSettings()
                        }.show()
                }
            }.show()
    }

    private fun showSettings() {
        route = Route.SETTINGS
        dashboardVisible = false
        selected.clear()
        val layout = root()
        layout.addView(action("‹ Retour à l’accueil") { showHome() })
        layout.addView(hero(R.drawable.hero_images, 110))
        layout.addView(heading("PARAMÈTRES"))
        layout.addView(note("Les catégories sont internes à l'application. Sauvegarde-les avant toute désinstallation : celle-ci peut effacer le classement."))
        layout.addView(heading("Mes catégories", 19f))
        savedCategories().forEach { category ->
            layout.addView(action("✎ $category") { manageCategory(category) }, LinearLayout.LayoutParams(-1, dp(47)))
        }
        layout.addView(action("+ Ajouter une catégorie") {
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
                    } else toast("Nom vide, trop long ou déjà utilisé.")
                }.setNegativeButton("Annuler", null).show()
        }, LinearLayout.LayoutParams(-1, dp(51)))
        layout.addView(heading("Sauvegarder ton classement", 19f))
        layout.addView(action("Exporter la sauvegarde JSON") { exportCategories() }, LinearLayout.LayoutParams(-1, dp(54)))
        layout.addView(action("Importer une sauvegarde JSON") { importCategories() }, LinearLayout.LayoutParams(-1, dp(54)))
        layout.addView(note("Une sauvegarde contient tes catégories et les identifiants des médias, pas les photos. Réimportation conçue pour la même médiathèque : si Android change ses identifiants, certaines associations ne reviendront pas."))
        layout.addView(heading("Sécurité des fichiers", 19f))
        layout.addView(note("Copier crée de nouveaux fichiers dans le dossier choisi sans toucher aux originaux. Corbeille agit sur les vrais fichiers après deux validations : aucune suppression définitive ni effacement sécurisé dans cette version."))
        layout.addView(note("Doublons : détection SHA-256 des photos strictement identiques de 50 Mo maximum ; aucune suppression automatique. Les photos visuellement similaires ne sont pas repérées."))
        layout.addView(note("Version 0.4 · Appui long sur une miniature pour sélectionner."))
        val screen=root()
        screen.addView(ScrollView(this).apply { addView(layout) },LinearLayout.LayoutParams(-1,0,1f))
        screen.addView(navBar(Route.SETTINGS))
        setContentView(screen)
    }

    @Deprecated("Used to support navigation on Android 8-12")
    override fun onBackPressed() {
        if (route == Route.HOME) super.onBackPressed()
        else if (dashboardVisible) showHome()
        else if (route == Route.IMAGES || route == Route.VIDEOS) showCategoryDashboard(route)
        else showHome()
    }

    override fun onDestroy() {
        generation++
        io.shutdownNow()
        super.onDestroy()
    }
}
