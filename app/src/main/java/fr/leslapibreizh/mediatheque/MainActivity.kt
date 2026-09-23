package fr.leslapibreizh.mediatheque

import android.Manifest
import android.app.AlertDialog
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.StatFs
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors
import java.security.MessageDigest
import java.io.File
import java.io.FileOutputStream

/**
 * Application Les Lapibreizh, base fonctionnelle V058 conservée.
 * Les fichiers originaux ne sont pas déplacés par le classement logique.
 * Toute mise à la corbeille requiert une confirmation explicite.
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
    private var duplicatePairs = emptyList<Pair<Media, Media>>()
    private var duplicateIndex = 0
    private val duplicateTrashSelection = linkedSetOf<String>()
    private var pendingTrashKeys = emptyList<String>()
    private var pendingCategoryImage: String? = null
    private enum class CategoryEntry { SETTINGS, IMAGES, VIDEOS }
    private var categoryEntry = CategoryEntry.SETTINGS
    private var videoCategoryTab = false
    private var pendingCategoryDeletion: String? = null
    private val videoCatalogCategories = listOf(
        "Montages vidéo", "Académie des Lapibreizh", "Fiches sportives",
        "Restaurant des Lapibreizh", "Voyages de Carnot", "Épisodes des Lapibreizh",
        "Personnages", "Lapins réels", "Cueillette", "Fiches vertes",
        "Fiches bleues", "Fiches orange", "Fiches rouges", "Fiches violettes",
        "Fiches bonus", "Bricolage")
    private var screenMode = "home"
    private var textSearch = ""
    private var pendingCategory: String? = null
    private var mediaSort = 0
    private var thumbnailColumns = 3
    // Les onglets de « À classer » filtrent les médias sur place, sans navigation.
    private enum class UnsortedFilter { ALL, PHOTOS, VIDEOS }
    private var unsortedFilter = UnsortedFilter.ALL
    private val canonicalCategories = listOf(
        "Académie des Lapibreizh", "Fiches couleurs", "Fiches sportives", "Bricolage",
        "Restaurant des Lapibreizh", "Voyages de Carnot", "Épisodes des Lapibreizh",
        "Personnages", "Lapins réels")
    private data class Hotspot(val x: Int, val y: Int, val w: Int, val h: Int,
        val label: String, val click: () -> Unit)
    private val artworkCategories = listOf(
        "Académie des Lapibreizh", "Fiches couleurs", "Fiches sportives", "Bricolage",
        "Restaurant des Lapibreizh", "Voyages de Carnot", "Épisodes des Lapibreizh",
        "Personnages", "Lapins réels", "Non classées")

    private enum class Route { HOME, IMAGES, VIDEOS, UNSORTED, FAVORITES, SETTINGS, CATEGORIES, SEARCH }
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
        // Réglages d’automatisation : valeurs par défaut posées une seule fois.
        // Les choix de l’utilisateur ne sont jamais réinitialisés au redémarrage.
        if (!prefs.getBoolean("automation_settings_initialized", false)) {
            prefs.edit()
                .putBoolean("auto_sort", false)
                .putBoolean("auto_suggest", true)
                .putBoolean("auto_create", false)
                .putBoolean("source_images", true)
                .putBoolean("source_videos", true)
                .putBoolean("source_screenshots", true)
                .putBoolean("source_downloads", true)
                .putBoolean("source_shares", true)
                .putBoolean("source_other", false)
                .putBoolean("automation_settings_initialized", true)
                .apply()
        }
        // Migration unique : préserver les anciennes catégories et ajouter Montages vidéo.
        if (!prefs.getBoolean("v064_video_category_seeded", false)) {
            val categories = savedCategories()
            prefs.edit().putString("category_names", categories.joinToString("\u001f"))
                .putBoolean("v064_video_category_seeded", true).apply()
        }
        migrateLegacyCategoryImagesToPrivateStorage()
        showHome()
    }

    /** V0.6.8 : convertit les anciennes URI de miniatures en copies privées sans toucher aux originaux. */
    private fun migrateLegacyCategoryImagesToPrivateStorage() {
        val dir=File(filesDir,"category_images").apply { mkdirs() }
        val edit=prefs.edit()
        var changed=false
        prefs.all.keys.filter { it.startsWith("category_image_") }.forEach { key ->
            val value=prefs.getString(key,null) ?: return@forEach
            if (value.startsWith("content://")) {
                val bitmap=try { contentResolver.openInputStream(Uri.parse(value))?.use { BitmapFactory.decodeStream(it) } } catch (_:Exception){ null }
                if (bitmap != null) {
                    val outW=960; val outH=600
                    val scale=maxOf(outW.toFloat()/bitmap.width,outH.toFloat()/bitmap.height)
                    val m=Matrix().apply {
                        postScale(scale,scale)
                        postTranslate((outW-bitmap.width*scale)/2f,(outH-bitmap.height*scale)/2f)
                    }
                    val cropped=Bitmap.createBitmap(outW,outH,Bitmap.Config.ARGB_8888).also { Canvas(it).drawBitmap(bitmap,m,Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)) }
                    val target=File(dir,"category_${System.currentTimeMillis()}_${key.hashCode()}.jpg")
                    val ok=try { FileOutputStream(target).use { cropped.compress(Bitmap.CompressFormat.JPEG,90,it) } } catch (_:Exception){ false }
                    if (ok && target.exists() && target.length()>0L) { edit.putString(key,target.absolutePath); changed=true } else target.delete()
                }
            }
        }
        if(changed) edit.apply()
    }

    private fun root(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(black)
        setPadding(dp(12), dp(10), dp(12), dp(9))
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
        backgroundTintList = null
        background = GradientDrawable().apply {
            cornerRadius = dp(13).toFloat()
            setColor(Color.rgb(25, 23, 20))
            setStroke(dp(1), gold)
        }
        setOnClickListener { onClick() }
    }

    private fun dp(n: Int) = (resources.displayMetrics.density * n + 0.5f).toInt()

    private fun hero(resId: Int, heightDp: Int): ImageView = ImageView(this).apply {
        setImageResource(resId)
        scaleType = ImageView.ScaleType.CENTER_CROP
        adjustViewBounds = true
        contentDescription = "Illustration Les Lapibreizh"
    }

    private fun settingsHeader(onBack: () -> Unit): FrameLayout = FrameLayout(this).apply {
        // Le visuel contient déjà « Retour » : ne pas ajouter un deuxième bouton en dessous.
        val image = ImageView(this@MainActivity).apply {
            setImageResource(R.drawable.art_settings_header)
            scaleType = ImageView.ScaleType.FIT_XY
            contentDescription = "En-tête Paramètres"
        }
        val width = resources.displayMetrics.widthPixels - dp(24)
        val imageHeight = (width * 121f / 868f).toInt().coerceAtLeast(dp(44))
        addView(image, FrameLayout.LayoutParams(-1, imageHeight))
        addView(View(this@MainActivity).apply {
            isClickable = true
            contentDescription = "Retour"
            setOnClickListener { onBack() }
        }, FrameLayout.LayoutParams(dp(104), imageHeight, Gravity.START))
    }

    private fun setUnsortedFilter(filter: UnsortedFilter) {
        if (route != Route.UNSORTED) return
        unsortedFilter = filter
        updateItems()
    }

    /** One native navigation bar, with the active destination highlighted. */
    private fun navBar(active: Route): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        setBackgroundColor(black)
        setPadding(dp(4), dp(4), dp(4), dp(3))
        val tabs: List<Triple<String, Route, () -> Unit>> = if (active == Route.UNSORTED) listOf(
            Triple("⌂\nAccueil", Route.HOME, { showHome() }),
            Triple("▣\nPhotos", Route.IMAGES, { setUnsortedFilter(UnsortedFilter.PHOTOS) }),
            Triple("▶\nVidéos", Route.VIDEOS, { setUnsortedFilter(UnsortedFilter.VIDEOS) }),
            Triple("▦\nTout", Route.UNSORTED, { setUnsortedFilter(UnsortedFilter.ALL) }),
            Triple("⚙\nParamètres", Route.SETTINGS, { showSettings() })
        ) else listOf(
            Triple("⌂\nAccueil", Route.HOME, { showHome() }),
            Triple("▧\nGalerie", Route.IMAGES, { showMediaDashboard(Route.IMAGES) }),
            Triple("▱\nCatégories", Route.CATEGORIES, {
                val video = active == Route.VIDEOS || (active == Route.CATEGORIES && videoCategoryTab)
                val origin=if (active==Route.CATEGORIES) categoryEntry
                    else if (video) CategoryEntry.VIDEOS else CategoryEntry.IMAGES
                showCategoryManager(video, origin)
            }),
            Triple("⌕\nRecherche", Route.SEARCH, {
                openSearch(if (active == Route.VIDEOS) Route.VIDEOS else Route.IMAGES)
            }),
            Triple("⚙\nParamètres", Route.SETTINGS, { showSettings() })
        )
        tabs.forEach { (label, tab, destination) ->
            val isActive = tab == active
            val cell = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                isClickable = true
                contentDescription = label.replace("\n", " ")
                setOnClickListener { destination() }
                addView(TextView(this@MainActivity).apply {
                    text = label.substringBefore('\n')
                    textSize = 24f
                    setTextColor(if (isActive) gold else Color.WHITE)
                    gravity = Gravity.CENTER
                }, LinearLayout.LayoutParams(-1, dp(35)))
                addView(TextView(this@MainActivity).apply {
                    text = label.substringAfter('\n')
                    textSize = 11f
                    setTextColor(if (isActive) gold else cream)
                    gravity = Gravity.CENTER
                    maxLines = 1
                }, LinearLayout.LayoutParams(-1, dp(22)))
            }
            addView(cell, LinearLayout.LayoutParams(0, dp(65), 1f))
        }
    }

    private fun panel(title: String, subtitle: String, onClick: () -> Unit): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setPadding(dp(10), dp(12), dp(10), dp(12))
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(16).toFloat()
            setColor(Color.rgb(22, 18, 14))
            setStroke(dp(1), Color.rgb(112, 83, 42))
        }
        addView(TextView(this@MainActivity).apply {
            text = title; textSize = 18f; setTextColor(gold); gravity = Gravity.CENTER
            typeface = android.graphics.Typeface.create("serif", android.graphics.Typeface.BOLD)
        })
        addView(TextView(this@MainActivity).apply {
            text = subtitle; textSize = 11.5f; setTextColor(cream); gravity = Gravity.CENTER
            setPadding(2, dp(5), 2, 0)
        })
        setOnClickListener { onClick() }
    }

    /** Artwork stays pixel-identical to approved reference; only hit zones are transparent. */
    private fun artworkScreen(resource: Int, artWidth: Int, artHeight: Int,
                              hits: List<Hotspot>, counts: Boolean = false,
                              extraAction: Pair<String, () -> Unit>? = null) {
        val width = resources.displayMetrics.widthPixels
        val height = (width.toFloat() * artHeight / artWidth).toInt()
        val factor = width.toFloat() / artWidth
        val frame = FrameLayout(this)
        frame.addView(ImageView(this).apply {
            setImageResource(resource)
            scaleType = ImageView.ScaleType.FIT_XY
            contentDescription = "Illustration Les Lapibreizh, commandes accessibles"
        }, FrameLayout.LayoutParams(width, height))
        // For image overview the printed sample counts are replaced by real local totals.
        if (counts) {
            val imageCatalog = resource == R.drawable.ui_images_exact
            val positions = if (imageCatalog) listOf(
                Triple(35, 544, 0), Triple(317, 544, 1), Triple(595, 544, 2),
                Triple(35, 783, 3), Triple(317, 783, 4), Triple(595, 783, 5),
                Triple(35, 1019, 6), Triple(317, 1019, 7), Triple(595, 1019, 8),
                Triple(35, 1226, 9)) else listOf(
                Triple(39, 864, 0), Triple(267, 864, 1),
                Triple(485, 864, 2), Triple(709, 864, 3),
                Triple(39, 1056, 4), Triple(267, 1056, 5),
                Triple(485, 1056, 6), Triple(709, 1056, 7),
                Triple(39, 1244, 8), Triple(267, 1244, 9))
            val labels = positions.map { (x,y,_) ->
                TextView(this).apply {
                    text = "— média"
                    textSize = 11f
                    setTextColor(cream)
                    setBackgroundColor(0xF0090807.toInt())
                    setPadding(dp(2), 0, dp(2), 0)
                }.also { label ->
                    frame.addView(label, FrameLayout.LayoutParams(
                        ((if (imageCatalog) 180 else 168) * factor).toInt(),
                        ((if (imageCatalog) 26 else 29) * factor).toInt()).apply {
                        leftMargin = (x * factor).toInt(); topMargin = (y * factor).toInt()
                    })
                }
            }
            if (hasPermission(Route.IMAGES)) io.execute {
                val all = queryMedia(Route.IMAGES)
                val grouped = all.groupingBy { getCategory(it) }.eachCount()
                runOnUiThread {
                    if (route == Route.IMAGES) labels.forEachIndexed { i, label ->
                        val category = artworkCategories[i]
                        label.text = "${if (category == "Non classées") grouped[""] ?: 0 else grouped[category] ?: 0} image(s)"
                    }
                }
            } else labels.forEach { it.text = "Accès requis" }
        }
        hits.forEach { hit ->
            val touch = View(this).apply {
                isClickable = true
                contentDescription = hit.label
                setOnClickListener { hit.click() }
            }
            frame.addView(touch, FrameLayout.LayoutParams(
                (hit.w * factor).toInt().coerceAtLeast(dp(30)),
                (hit.h * factor).toInt().coerceAtLeast(dp(36))).apply {
                leftMargin = (hit.x * factor).toInt()
                topMargin = (hit.y * factor).toInt()
            })
        }
        setContentView(ScrollView(this).apply {
            isFillViewport = false
            setBackgroundColor(black)
            val content = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
            if (extraAction != null) {
                content.addView(action(extraAction.first) { extraAction.second.invoke() }.apply {
                    textSize = 15f
                    contentDescription = extraAction.first
                }, LinearLayout.LayoutParams(-1, dp(50)).apply {
                    setMargins(dp(12), dp(4), dp(12), dp(4))
                })
            }
            content.addView(frame, LinearLayout.LayoutParams(width, height))
            addView(content)
        })
    }

    private fun hs(x: Int, y: Int, w: Int, h: Int, label: String,
                   click: () -> Unit) = Hotspot(x, y, w, h, label, click)

    private fun openSearch(kind: Route) {
        val field = EditText(this).apply {
            hint = "Nom, album, catégorie ou capture d’écran"
            setSingleLine(true); setTextColor(Color.BLACK)
            setText(textSearch)
        }
        AlertDialog.Builder(this).setTitle("Recherche des médias")
            .setView(field)
            .setPositiveButton("Rechercher") { _, _ ->
                textSearch = field.text.toString().trim()
                navigate(kind)
            }
            .setNeutralButton("Effacer") { _, _ -> textSearch = ""; navigate(kind) }
            .setNegativeButton("Annuler", null).show()
    }

    private fun resolveCategory(name: String): String {
        var resolved = name
        repeat(12) {
            val next = prefs.getString("category_alias_$resolved", null)
            if (next.isNullOrBlank() || next == resolved) return resolved
            resolved = next
        }
        return resolved
    }

    private fun openGalleryCategory(category: String, kind: Route = Route.IMAGES) {
        pendingCategory = if (category == "Non classées") "" else resolveCategory(category)
        navigate(if (category == "Non classées") Route.UNSORTED else kind)
    }

    private fun showHome() {
        generation++
        screenMode = "home"
        route = Route.HOME
        selected.clear()
        artworkScreen(R.drawable.ui_home_exact, 906, 1736, listOf(
            hs(15,743,423,405,"Images") { showMediaDashboard(Route.IMAGES) },
            hs(454,743,427,405,"Montages vidéo") { showVideosCategories() },
            hs(16,1164,207,186,"Rechercher") { openSearch(Route.IMAGES) },
            hs(237,1164,207,186,"Non classées") { navigate(Route.UNSORTED) },
            hs(455,1164,208,186,"Galerie") { navigate(Route.IMAGES) },
            hs(674,1164,209,186,"Paramètres") { showSettings() }
        ))
    }

    private fun showMediaDashboard(kind: Route) {
        generation++
        screenMode = "dashboard"
        route = Route.IMAGES
        selected.clear()
        artworkScreen(R.drawable.ui_mediatheque_exact, 941, 1672, listOf(
            hs(799,8,56,57,"Rechercher") { openSearch(Route.IMAGES) },
            hs(864,8,53,57,"Paramètres et options") { showSettings() },
            hs(22,342,695,59,"Rechercher une image") { openSearch(Route.IMAGES) },
            hs(733,342,174,59,"Filtres") { openFilters() },
            hs(22,411,440,289,"Images") { showImagesCategories() },
            hs(473,411,434,289,"Montages vidéo") { showVideosCategories() },
            hs(22,710,216,185,"Académie") { openGalleryCategory(artworkCategories[0]) },
            hs(249,710,210,185,"Fiches couleurs") { openGalleryCategory(artworkCategories[1]) },
            hs(471,710,210,185,"Fiches sportives") { openGalleryCategory(artworkCategories[2]) },
            hs(692,710,216,185,"Bricolage") { openGalleryCategory(artworkCategories[3]) },
            hs(22,906,216,185,"Restaurant") { openGalleryCategory(artworkCategories[4]) },
            hs(249,906,210,185,"Voyages") { openGalleryCategory(artworkCategories[5]) },
            hs(471,906,210,185,"Épisodes") { openGalleryCategory(artworkCategories[6]) },
            hs(692,906,216,185,"Personnages") { openGalleryCategory(artworkCategories[7]) },
            hs(22,1097,216,185,"Lapins réels") { openGalleryCategory(artworkCategories[8]) },
            hs(249,1097,210,185,"Non classées") { navigate(Route.UNSORTED) },
            hs(22,1296,216,110,"Retrouver") { openSearch(Route.IMAGES) },
            hs(249,1296,210,110,"Trier") { navigate(Route.IMAGES) },
            hs(471,1296,210,110,"Voir la galerie") { navigate(Route.IMAGES) },
            hs(692,1296,216,110,"Paramètres") { showSettings() },
            hs(18,1421,190,127,"Accueil") { showHome() },
            hs(209,1421,170,127,"Images") { showImagesCategories() },
            hs(379,1421,188,127,"Vidéos") { showVideosCategories() },
            hs(567,1421,183,127,"Favoris") { navigate(Route.FAVORITES) },
            hs(750,1421,174,127,"Paramètres") { showSettings() }
        ), counts = true)
    }

    private fun showImagesCategories() = showCategoryCatalog(false)

    private fun showVideosCategories() = showCategoryCatalog(true)

    /** V0.6.8: catalogue réellement dynamique et défilant. */
    private fun showCategoryCatalog(video: Boolean) {
        generation++
        screenMode = if (video) "videos-catalog" else "images-catalog"
        route = if (video) Route.VIDEOS else Route.IMAGES
        videoCategoryTab = video
        selected.clear()

        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(black)
        }
        val content = root()
        content.addView(brandHeader("Retour", if (video) "Mes vidéos" else "Mes images") {
            showMediaDashboard(Route.IMAGES)
        })
        content.addView(simpleLabel(if (video) "▶  CATÉGORIES VIDÉOS" else "▧  CATÉGORIES IMAGES", 22f, gold))
        content.addView(simpleLabel("Fais défiler, touche une catégorie pour l’ouvrir, ou + pour en créer une.", 12f, cream))

        val grid = GridLayout(this).apply {
            columnCount = 2
            alignmentMode = GridLayout.ALIGN_BOUNDS
            useDefaultMargins = false
        }
        fun addTile(category: String) {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(5), dp(5), dp(5), dp(7))
                background = GradientDrawable().apply {
                    setColor(Color.rgb(18,18,17)); cornerRadius = dp(12).toFloat(); setStroke(dp(1), Color.rgb(87,79,64))
                }
                isClickable = true
                contentDescription = category
                setOnClickListener { openGalleryCategory(category, if (video) Route.VIDEOS else Route.IMAGES) }
                addView(currentCategoryPhoto(category), LinearLayout.LayoutParams(-1, dp(118)))
                addView(simpleLabel(category, 14f, gold).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(-1, dp(42)))
                addView(action("Modifier") { showCategoryEditor(category) }, LinearLayout.LayoutParams(-1, dp(42)))
            }
            val lp = GridLayout.LayoutParams().apply {
                width = 0; height = dp(215); columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                setMargins(dp(4), dp(4), dp(4), dp(4))
            }
            grid.addView(card, lp)
        }
        categoriesForTab(video).forEach(::addTile)

        val plus = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; isClickable = true
            background = GradientDrawable().apply {
                setColor(Color.rgb(22,18,14)); cornerRadius=dp(12).toFloat(); setStroke(dp(2),gold)
            }
            addView(simpleLabel("+", 58f, gold).apply { gravity=Gravity.CENTER }, LinearLayout.LayoutParams(-1,dp(120)))
            addView(simpleLabel("Créer une catégorie", 14f, cream).apply { gravity=Gravity.CENTER }, LinearLayout.LayoutParams(-1,dp(55)))
            setOnClickListener { addCategoryDialog() }
        }
        grid.addView(plus, GridLayout.LayoutParams().apply {
            width=0; height=dp(215); columnSpec=GridLayout.spec(GridLayout.UNDEFINED,1f)
            setMargins(dp(4),dp(4),dp(4),dp(4))
        })
        content.addView(grid, LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(8); bottomMargin=dp(8) })
        if (video) content.addView(action("▶  Autres vidéos · médias non classés") { openOtherVideos() }, LinearLayout.LayoutParams(-1,dp(50)))
        val scroll = ScrollView(this).apply { setBackgroundColor(black); addView(content) }
        page.addView(scroll, LinearLayout.LayoutParams(-1,0,1f))
        page.addView(navBar(if (video) Route.VIDEOS else Route.IMAGES), LinearLayout.LayoutParams(-1,dp(75)))
        setContentView(page)
    }

    private fun openOtherVideos() {
        // « Autres vidéos » est la vue des vidéos sans catégorie Lapibreizh.
        pendingCategory = ""
        navigate(Route.VIDEOS)
    }

    private fun openFilters() {
        AlertDialog.Builder(this).setTitle("Filtres")
            .setItems(arrayOf("Toutes mes images", "Tous mes montages vidéo", "Non classées", "Favoris")) { _, i ->
                when(i) { 0 -> navigate(Route.IMAGES); 1 -> navigate(Route.VIDEOS)
                    2 -> navigate(Route.UNSORTED); else -> navigate(Route.FAVORITES) }
            }.show()
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
            if (hasPermission(route)) showGallery(route)
            else AlertDialog.Builder(this).setTitle("Accès aux médias")
                .setMessage("Autorise les photos ou vidéos pour afficher ta bibliothèque. Aucun fichier ne sera modifié.")
                .setPositiveButton("Compris") { _, _ -> showHome() }
                .show()
        }
    }

    private fun galleryHeader(isVideo: Boolean, onBack: () -> Unit): FrameLayout = FrameLayout(this).apply {
        val image = ImageView(this@MainActivity).apply {
            setImageResource(if (isVideo) R.drawable.art_gallery_videos else R.drawable.art_gallery_images)
            scaleType = ImageView.ScaleType.FIT_XY
            contentDescription = if (isVideo) "En-tête Vidéos" else "En-tête Images"
        }
        val width = resources.displayMetrics.widthPixels - dp(24)
        val imageHeight = (width * (if (isVideo) 275f else 239f) / 864f).toInt()
            .coerceAtLeast(dp(70))
        addView(image, FrameLayout.LayoutParams(-1, imageHeight))
        addView(View(this@MainActivity).apply {
            isClickable = true
            contentDescription = "Retour"
            setOnClickListener { onBack() }
        }, FrameLayout.LayoutParams(dp(95), dp(54), Gravity.TOP or Gravity.START))
    }

    private fun showGallery(next: Route) {
        route = next
        selected.clear()
        albumFilter = null
        categoryFilter = pendingCategory
        pendingCategory = null
        visibleLimit = 120
        if (next == Route.UNSORTED) unsortedFilter = UnsortedFilter.ALL
        loadGallery()
    }

    private fun loadGallery() {
        screenMode = "gallery"
        val requestNumber = ++generation
        val layout = root()
        val title = when (route) {
            Route.IMAGES -> "MES IMAGES"
            Route.VIDEOS -> "MONTAGES VIDÉO — EDITS"
            Route.FAVORITES -> "MES FAVORIS"
            else -> "À CLASSER"
        }
        layout.addView(galleryHeader(route == Route.VIDEOS) {
            if (route == Route.IMAGES) showImagesCategories()
            else if (route == Route.VIDEOS) showVideosCategories()
            else showMediaDashboard(Route.IMAGES)
        })
        layout.addView(heading(title, 18f))
        if (route == Route.UNSORTED) {
            val types = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            types.addView(action("Tout") { setUnsortedFilter(UnsortedFilter.ALL) },
                LinearLayout.LayoutParams(0, dp(46), 1f))
            types.addView(action("Photos") { setUnsortedFilter(UnsortedFilter.PHOTOS) },
                LinearLayout.LayoutParams(0, dp(46), 1f))
            types.addView(action("Vidéos") { setUnsortedFilter(UnsortedFilter.VIDEOS) },
                LinearLayout.LayoutParams(0, dp(46), 1f))
            layout.addView(types)
        }
        val info = note("Chargement des médias…")
        selectionInfo = info
        layout.addView(info)
        val search = EditText(this).apply {
            hint = "⌕  Rechercher une image ou vidéo…"
            setSingleLine(true)
            setText(textSearch)
            textSize = 15f
            setTextColor(cream)
            setHintTextColor(gold)
            background = GradientDrawable().apply {
                setColor(Color.rgb(29, 27, 23)); cornerRadius = dp(14).toFloat()
                setStroke(dp(1), gold)
            }
            setPadding(dp(15), dp(8), dp(10), dp(8))
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    textSearch = s?.toString() ?: ""
                    updateItems()
                }
                override fun afterTextChanged(s: android.text.Editable?) {}
            })
        }
        layout.addView(search, LinearLayout.LayoutParams(-1, dp(52)).apply {
            bottomMargin = dp(7)
        })
        val filters = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        filters.addView(action("Albums") { chooseAlbum() }, LinearLayout.LayoutParams(0, dp(50), 1f))
        filters.addView(action("Catégories") { chooseCategory() }, LinearLayout.LayoutParams(0, dp(50), 1f))
        filters.addView(action("Trier") {
            AlertDialog.Builder(this).setTitle("Trier par")
                .setSingleChoiceItems(arrayOf("Date récente", "Date ancienne", "Nom", "Type", "Taille"),mediaSort) { dialog, index ->
                    mediaSort = index; updateItems(); dialog.dismiss()
                }.setNegativeButton("Annuler",null).show()
        }, LinearLayout.LayoutParams(0, dp(50), 1f))
        layout.addView(filters)
        val displayOptions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        displayOptions.addView(action("Petites") { thumbnailColumns=4; grid?.numColumns=4; galleryAdapter?.notifyDataSetChanged() },
            LinearLayout.LayoutParams(0, dp(41), 1f))
        displayOptions.addView(action("Moyennes") { thumbnailColumns=3; grid?.numColumns=3; galleryAdapter?.notifyDataSetChanged() },
            LinearLayout.LayoutParams(0, dp(41), 1f))
        displayOptions.addView(action("Grandes") { thumbnailColumns=2; grid?.numColumns=2; galleryAdapter?.notifyDataSetChanged() },
            LinearLayout.LayoutParams(0, dp(41), 1f))
        layout.addView(displayOptions)
        selectionActions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            visibility = View.GONE
        }.also { actions ->
            actions.addView(action("Classer") { showMoveSelected() }, LinearLayout.LayoutParams(0, dp(49), 1f))
            actions.addView(action("Supprimer") { showDeleteSelected() }, LinearLayout.LayoutParams(0, dp(49), 1f))
            actions.addView(action("Partager") { shareSelected() }, LinearLayout.LayoutParams(0, dp(49), 1f))
            actions.addView(action("Favoris ♡") { toggleFavoritesSelected() }, LinearLayout.LayoutParams(0, dp(49), 1f))
            actions.addView(action("Annuler") { selected.clear(); updateSelection() }, LinearLayout.LayoutParams(0, dp(49), 1f))
            layout.addView(actions)
        }
        val g = GridView(this).apply {
            numColumns = thumbnailColumns
            horizontalSpacing = dp(5)
            verticalSpacing = dp(9)
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
                val availableWidth = resources.displayMetrics.widthPixels - dp(24) -
                    dp(5) * (thumbnailColumns - 1)
                val cardWidth = availableWidth / thumbnailColumns
                val portraitHeight = (cardWidth * 16f / 9f).toInt().coerceAtMost(dp(520))
                val frame = FrameLayout(this@MainActivity).apply {
                    layoutParams = AbsListView.LayoutParams(-1, portraitHeight + dp(52))
                }
                val image = ImageView(this@MainActivity).apply {
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    setBackgroundColor(this@MainActivity.background)
                    tag = media.key
                }
                frame.addView(image, FrameLayout.LayoutParams(-1, portraitHeight, Gravity.TOP))
                val caption = TextView(this@MainActivity).apply {
                    text = (if (media.mime.startsWith("video/")) "▶ " else "") + media.title
                    textSize = 13f
                    maxLines = 2
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setTextColor(cream)
                    setBackgroundColor(0xCC080705.toInt())
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(4), dp(2), dp(4), dp(2))
                }
                frame.addView(caption, FrameLayout.LayoutParams(-1, dp(52), Gravity.BOTTOM))
                if (prefs.getBoolean("favorite_${media.key}", false)) {
                    frame.addView(TextView(this@MainActivity).apply {
                        text = "♥"; textSize = 20f; setTextColor(gold)
                        setBackgroundColor(0xAA080705.toInt())
                    }, FrameLayout.LayoutParams(dp(26),dp(26),Gravity.TOP or Gravity.START))
                }
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
                if (selected.contains(media.key)) selected.remove(media.key)
                else if (selected.size < 1000) selected.add(media.key)
                else Toast.makeText(this,"1 000 éléments maximum pour déplacer / classer",Toast.LENGTH_SHORT).show()
                updateSelection()
            }
        }
        g.setOnItemLongClickListener { _, _, position, _ ->
            val key = shownItems[position].key
            if (selected.contains(key)) selected.remove(key)
            else if (selected.size < 1000) selected.add(key)
            else Toast.makeText(this,"1 000 éléments maximum pour déplacer / classer",Toast.LENGTH_SHORT).show()
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
        layout.addView(navBar(route))
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
        return when (target) {
            Route.UNSORTED -> result.filter { getCategory(it).isEmpty() }
            Route.FAVORITES -> result.filter { prefs.getBoolean("favorite_${it.key}",false) }
            else -> result
        }
    }

    private fun thumbnail(media: Media): Bitmap? {
        if (Build.VERSION.SDK_INT >= 29) return contentResolver.loadThumbnail(media.uri, Size(540, 960), null)
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

    private fun matchesSearch(media: Media, query: String): Boolean {
        val term = query.trim()
        if (term.isEmpty()) return true
        val fields = listOf(media.title, media.album, getCategory(media), media.mime)
        if (fields.any { it.contains(term, ignoreCase = true) }) return true
        // Les captures peuvent porter un nom français dans le dossier du téléphone.
        if (term.equals("screenshot", true) || term.equals("screenshots", true)) {
            return fields.any { field ->
                listOf("capture d'écran", "captures d'écran", "capture d’écran",
                    "captures d’écran", "capture ecran", "captures ecran", "screen shot")
                    .any { field.contains(it, ignoreCase = true) }
            }
        }
        return false
    }

    private fun updateItems() {
        val filtered = galleryItems.filter { media ->
            (albumFilter == null || media.album == albumFilter) &&
                (categoryFilter == null || getCategory(media) == categoryFilter) &&
                (route != Route.UNSORTED || getCategory(media).isEmpty()) &&
                (route != Route.UNSORTED || when (unsortedFilter) {
                    UnsortedFilter.ALL -> true
                    UnsortedFilter.PHOTOS -> !media.mime.startsWith("video/")
                    UnsortedFilter.VIDEOS -> media.mime.startsWith("video/")
                }) &&
                (route != Route.FAVORITES || prefs.getBoolean("favorite_${media.key}", false)) &&
                matchesSearch(media, textSearch)
        }
        shownItems = when (mediaSort) {
            1 -> filtered.sortedBy { it.date }
            2 -> filtered.sortedBy { it.title.lowercase() }
            3 -> filtered.sortedBy { it.mime }
            4 -> filtered.sortedByDescending { it.size }
            else -> filtered.sortedByDescending { it.date }
        }
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
        val categories = listOf("Toutes les catégories", "Non classées") + savedCategories()
        AlertDialog.Builder(this).setTitle("Classement Lapibreizh")
            .setItems(categories.toTypedArray()) { _, index ->
                categoryFilter = when (index) { 0 -> null; 1 -> ""; else -> categories[index] }
                updateItems()
            }.show()
    }

    private fun getCategory(media: Media): String = prefs.getString("media_${media.key}", "") ?: ""

    private fun savedCategories(): List<String> {
        val raw = prefs.getString("category_names", null)
        val saved = if (raw == null) canonicalCategories + listOf(
            "Fiches vertes", "Fiches bleues", "Fiches orange", "Fiches rouges",
            "Fiches violettes", "Fiches bonus", "Cueillette", "Autres")
            else raw.split('\u001f').filter { it.isNotBlank() }
        // Migration non destructive : « Montages vidéo » devient une vraie catégorie
        // sans retirer ni renommer celles déjà enregistrées.
        return if (raw == null) (saved + "Montages vidéo").distinct() else saved.distinct()
    }

    private fun categoryScope(name: String): String =
        prefs.getString("category_scope_$name", null)
            ?: if (name == "Montages vidéo") "video" else "both"

    private fun categoriesForTab(video: Boolean): List<String> = savedCategories().filter { name ->
        val scope = categoryScope(name)
        if (video) scope == "video" || (scope == "both" &&
            (name in videoCatalogCategories || prefs.getBoolean("category_video_$name",false) ||
                prefs.all.any { (k, v) -> k.startsWith("media_v:") && v == name }))
        else scope != "video"
    }

    private fun classifySelected() {
        val cats = savedCategories()
        val choices = (listOf("Non classées") + cats).toTypedArray()
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

    private fun toggleFavoritesSelected() {
        val keys = selected.toList()
        if (keys.isEmpty()) return
        val allFavorite = keys.all { prefs.getBoolean("favorite_$it",false) }
        val edit = prefs.edit()
        keys.forEach { edit.putBoolean("favorite_$it", !allFavorite) }
        edit.apply()
        selected.clear()
        updateSelection()
        updateItems()
        Toast.makeText(this, if (allFavorite) "Favoris retirés" else "Ajoutés aux favoris", Toast.LENGTH_SHORT).show()
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

    private fun section(title: String, subtitle: String? = null): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = GradientDrawable().apply {
                setColor(Color.rgb(20, 18, 14))
                cornerRadius = dp(16).toFloat()
                setStroke(dp(1), Color.rgb(82, 67, 39))
            }
            addView(simpleLabel(title, 17f, gold))
            if (!subtitle.isNullOrBlank()) addView(simpleLabel(subtitle, 12f, cream))
        }

    private fun switchRow(label: String, key: String, enabled: Boolean = true): Switch =
        Switch(this).apply {
            text = label
            textSize = 14f
            setTextColor(cream)
            isChecked = if (enabled) prefs.getBoolean(key, true) else false
            isEnabled = enabled
            setPadding(dp(8), dp(7), dp(8), dp(7))
            setOnCheckedChangeListener { _, checked ->
                if (isEnabled) prefs.edit().putBoolean(key, checked).apply()
            }
        }

    private fun primaryAction(text: String, onClick: () -> Unit) = action(text, onClick).apply {
        setTextColor(Color.rgb(18, 14, 8))
        textSize = 15f
        background = GradientDrawable().apply {
            cornerRadius = dp(14).toFloat()
            setColor(Color.rgb(235, 190, 88))
            setStroke(dp(1), Color.rgb(255, 218, 125))
        }
    }

    private fun dangerAction(text: String, onClick: () -> Unit) = action(text, onClick).apply {
        setTextColor(Color.WHITE)
        textSize = 15f
        background = GradientDrawable().apply {
            cornerRadius = dp(14).toFloat()
            setColor(Color.rgb(190, 48, 45))
            setStroke(dp(1), Color.rgb(255, 90, 82))
        }
    }

    private fun visualTitle(icon: String, title: String, subtitle: String? = null): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(12), dp(8), dp(10))
            addView(heading("$icon  $title", 27f))
            if (!subtitle.isNullOrBlank()) addView(note(subtitle))
        }

    // En-tête compact : retour entièrement visible, identité centrée et filet doré inférieur.
    private fun brandHeader(backLabel: String, page: String, onBack: () -> Unit): LinearLayout =
        LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL
            setBackgroundColor(black)
            val horizontal=LinearLayout(this@MainActivity).apply {
                orientation=LinearLayout.HORIZONTAL
                gravity=Gravity.CENTER_VERTICAL
            }
            horizontal.addView(TextView(this@MainActivity).apply {
                text="‹  $backLabel";textSize=12f;setTextColor(gold)
                gravity=Gravity.START or Gravity.CENTER_VERTICAL
                maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END
                isClickable=true;isFocusable=true
                contentDescription="Retour vers $backLabel"
                setOnClickListener {onBack()}
            },LinearLayout.LayoutParams(0,dp(72),0.28f))
            horizontal.addView(ImageView(this@MainActivity).apply {
                setImageResource(R.drawable.ui_brand_center)
                scaleType=ImageView.ScaleType.FIT_CENTER
                contentDescription="Les Lapibreizh"
            },LinearLayout.LayoutParams(0,dp(72),0.42f))
            horizontal.addView(TextView(this@MainActivity).apply {
                text=page;textSize=12f;setTextColor(gold)
                gravity=Gravity.END or Gravity.CENTER_VERTICAL
                maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END
                typeface=android.graphics.Typeface.create("serif",android.graphics.Typeface.BOLD)
            },LinearLayout.LayoutParams(0,dp(72),0.30f))
            addView(horizontal,LinearLayout.LayoutParams(-1,dp(72)))
            addView(View(this@MainActivity).apply {setBackgroundColor(gold)},
                LinearLayout.LayoutParams(-1,dp(1)))
        }

    private fun scenicImage(res: Int, height: Int): ImageView = ImageView(this).apply {
        setImageResource(res)
        scaleType = ImageView.ScaleType.CENTER_CROP
        contentDescription = "Illustration bretonne Les Lapibreizh"
        background = GradientDrawable().apply {
            cornerRadius = dp(12).toFloat()
            setColor(this@MainActivity.background)
        }
        clipToOutline = true
        minimumHeight = dp(height)
    }

    private fun rowCard(icon: String, title: String, detail: String, click: () -> Unit): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(11), dp(10), dp(10), dp(10))
            background = GradientDrawable().apply {
                setColor(Color.rgb(24, 23, 21))
                cornerRadius = dp(12).toFloat()
                setStroke(dp(1), Color.rgb(105, 92, 69))
            }
            addView(TextView(this@MainActivity).apply {
                text = icon; textSize = 27f; setTextColor(gold)
                gravity = Gravity.CENTER
            }, LinearLayout.LayoutParams(dp(45), dp(52)))
            val labels = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
            labels.addView(TextView(this@MainActivity).apply {
                text = title; textSize = 15f; setTextColor(cream)
            })
            labels.addView(TextView(this@MainActivity).apply {
                text = detail; textSize = 11f; setTextColor(Color.rgb(201, 191, 172))
            })
            addView(labels, LinearLayout.LayoutParams(0, -2, 1f))
            addView(TextView(this@MainActivity).apply {
                text = "›"; textSize = 26f; setTextColor(gold); gravity = Gravity.CENTER
            }, LinearLayout.LayoutParams(dp(25), dp(50)))
            isClickable = true
            setOnClickListener { click() }
        }

    private fun simpleLabel(value: String, size: Float = 14f, color: Int = cream): TextView =
        TextView(this).apply {
            text = value; textSize = size; setTextColor(color)
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }

    private fun questionMarkBitmap(): Bitmap {
        val w=480; val h=300
        return Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888).also { bitmap ->
            val canvas=Canvas(bitmap)
            canvas.drawColor(Color.rgb(18,18,17))
            val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply { color=gold; textSize=150f; textAlign=Paint.Align.CENTER; typeface=android.graphics.Typeface.DEFAULT_BOLD }
            val y=h/2f-(paint.ascent()+paint.descent())/2f
            canvas.drawText("?",w/2f,y,paint)
        }
    }

    private fun currentCategoryPhoto(category: String): ImageView = ImageView(this).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        contentDescription = "Photographie de la catégorie $category"
        val path = prefs.getString("category_image_$category", null)
        val file = path?.let { File(it) }
        if (file != null && file.exists()) {
            setImageBitmap(BitmapFactory.decodeFile(file.absolutePath))
        } else {
            setImageBitmap(questionMarkBitmap())
        }
    }

    private fun visualTitleArt(iconRes: Int, title: String, subtitle: String): LinearLayout =
        LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL
            setPadding(dp(8),dp(14),dp(8),dp(14))
            val headline=LinearLayout(this@MainActivity).apply {
                orientation=LinearLayout.HORIZONTAL
                gravity=Gravity.CENTER
            }
            headline.addView(ImageView(this@MainActivity).apply {
                setImageResource(iconRes)
                scaleType=ImageView.ScaleType.FIT_CENTER
                contentDescription="Icône de l'écran"
            },LinearLayout.LayoutParams(dp(58),dp(62)))
            headline.addView(heading(title,24f),LinearLayout.LayoutParams(0,-2,1f))
            addView(headline)
            addView(note(subtitle))
        }

    private fun visualFooter(): ImageView = hero(R.drawable.art_footer_bretagne, 145)

    /** V066 — native controls arranged like the approved compact gold mock-up.
     *  Settings controls are persistent; the classification engine is connected in a later phase.
     */
    private fun compactPanel(title: String, subtitle: String = ""): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(9), dp(7), dp(9), dp(8))
            background = GradientDrawable().apply {
                setColor(Color.rgb(15, 14, 12))
                cornerRadius = dp(11).toFloat()
                setStroke(dp(1), Color.rgb(111, 92, 57))
            }
            addView(simpleLabel(title, 15f, gold))
            if (subtitle.isNotBlank()) addView(simpleLabel(subtitle, 10f, cream))
        }

    private fun compactLine(symbol: String, title: String, detail: String = "", onClick: (() -> Unit)? = null): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(5), dp(5), dp(5), dp(5))
            addView(simpleLabel(symbol, 20f, gold), LinearLayout.LayoutParams(dp(28), dp(39)))
            val labels = LinearLayout(this@MainActivity).apply { orientation=LinearLayout.VERTICAL }
            labels.addView(simpleLabel(title, 12f, cream))
            if (detail.isNotBlank()) labels.addView(simpleLabel(detail, 10f, Color.rgb(196,185,166)))
            addView(labels, LinearLayout.LayoutParams(0, -2, 1f))
            if (onClick != null) {
                addView(simpleLabel("›", 23f, gold), LinearLayout.LayoutParams(dp(20), dp(39)))
                isClickable=true
                isFocusable=true
                setOnClickListener { onClick() }
            }
        }

    private fun settingsToggle(enabledVisual: Boolean = true): TextView = TextView(this).apply {
        text = if (enabledVisual) "●" else "○"
        textSize = 17f
        gravity = Gravity.CENTER
        setTextColor(if (enabledVisual) black else cream)
        background = GradientDrawable().apply {
            cornerRadius = dp(18).toFloat()
            setColor(if (enabledVisual) Color.rgb(239, 193, 76) else Color.rgb(76, 76, 76))
            setStroke(dp(1), if (enabledVisual) gold else Color.DKGRAY)
        }
        alpha = if (enabledVisual) 1f else 0.75f
    }

    private fun settingsPrefToggle(key: String, defaultValue: Boolean): TextView {
        fun applyState(view: TextView, checked: Boolean) {
            view.text = if (checked) "●" else "○"
            view.setTextColor(if (checked) black else cream)
            view.background = GradientDrawable().apply {
                cornerRadius = dp(18).toFloat()
                setColor(if (checked) Color.rgb(239, 193, 76) else Color.rgb(76, 76, 76))
                setStroke(dp(1), if (checked) gold else Color.DKGRAY)
            }
            view.alpha = if (checked) 1f else 0.82f
            view.contentDescription = if (checked) "Activé" else "Désactivé"
        }
        return TextView(this).apply {
            textSize = 17f
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            applyState(this, prefs.getBoolean(key, defaultValue))
            setOnClickListener {
                val value = !prefs.getBoolean(key, defaultValue)
                prefs.edit().putBoolean(key, value).apply()
                applyState(this, value)
            }
        }
    }

    private fun settingsButton(symbol: String, title: String, detail: String, onClick: () -> Unit): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(7), dp(8), dp(7))
            background = GradientDrawable().apply {
                cornerRadius = dp(9).toFloat()
                setColor(Color.rgb(18, 18, 17))
                setStroke(dp(1), Color.rgb(105, 99, 88))
            }
            addView(simpleLabel(symbol, 24f, gold), LinearLayout.LayoutParams(dp(38), dp(50)))
            val txt = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
            txt.addView(simpleLabel(title, 13f, cream))
            txt.addView(simpleLabel(detail, 9.5f, Color.rgb(205, 197, 184)))
            addView(txt, LinearLayout.LayoutParams(0, -2, 1f))
            addView(simpleLabel("›", 25f, gold), LinearLayout.LayoutParams(dp(20), dp(46)))
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }

    private fun prefLine(symbol: String, title: String, detail: String, key: String, defaultValue: Boolean): LinearLayout =
        LinearLayout(this).apply {
            orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER_VERTICAL
            setPadding(dp(5),dp(3),dp(5),dp(3))
            addView(simpleLabel(symbol,18f,gold),LinearLayout.LayoutParams(dp(28),dp(42)))
            val labels=LinearLayout(this@MainActivity).apply { orientation=LinearLayout.VERTICAL }
            labels.addView(simpleLabel(title,12f,cream))
            if(detail.isNotBlank()) labels.addView(simpleLabel(detail,9.5f,Color.rgb(196,185,166)))
            addView(labels,LinearLayout.LayoutParams(0,-2,1f))
            addView(settingsPrefToggle(key,defaultValue),LinearLayout.LayoutParams(dp(48),dp(30)))
        }

    private fun choiceLine(symbol:String,title:String,value:String,onClick:()->Unit):LinearLayout =
        LinearLayout(this).apply {
            orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL
            setPadding(dp(5),dp(3),dp(5),dp(3))
            addView(simpleLabel(symbol,18f,gold),LinearLayout.LayoutParams(dp(28),dp(42)))
            addView(simpleLabel(title,12f,cream),LinearLayout.LayoutParams(dp(88),dp(42)))
            val field=simpleLabel("$value   ⌄",12f,cream).apply {
                gravity=Gravity.CENTER_VERTICAL
                background=GradientDrawable().apply { setColor(Color.rgb(18,18,17));cornerRadius=dp(8).toFloat();setStroke(dp(1),Color.rgb(105,99,88)) }
                isClickable=true;setOnClickListener{onClick()}
            }
            addView(field,LinearLayout.LayoutParams(0,dp(38),1f))
        }

    private fun themeSelector(): LinearLayout = LinearLayout(this).apply {
        orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL
        addView(simpleLabel("◉",18f,gold),LinearLayout.LayoutParams(dp(28),dp(42)))
        addView(simpleLabel("Thème",12f,cream),LinearLayout.LayoutParams(dp(70),dp(42)))
        val current=prefs.getString("theme","Sombre") ?: "Sombre"
        listOf("Clair","Sombre","Système").forEach { name ->
            val selectedTheme=name==current
            addView(action(name) { prefs.edit().putString("theme",name).apply(); showSettings() }.apply {
                textSize=10f
                setTextColor(if(selectedTheme) black else cream)
                background=GradientDrawable().apply {
                    cornerRadius=dp(7).toFloat();setStroke(dp(1),if(selectedTheme) gold else Color.rgb(105,99,88))
                    setColor(if(selectedTheme) Color.rgb(239,193,76) else Color.rgb(18,18,17))
                }
            },LinearLayout.LayoutParams(0,dp(38),1f).apply { marginEnd=dp(3) })
        }
    }

    /** V067 — esprit graphique de la maquette Paramètres validée.
     * La page peut défiler : priorité au rendu noir/or, aux vrais boutons et à la lisibilité.
     * Les réglages d’automatisation sont persistants ; le moteur de classement sera branché séparément.
     */
    private fun showSettings() {
        generation++; screenMode="settings"; route=Route.SETTINGS; selected.clear()
        val content=root().apply { setPadding(dp(8),dp(5),dp(8),dp(8)) }
        content.addView(brandHeader("Retour","⚙ Paramètres",{showHome()}))
        content.addView(visualTitle("⚙","Paramètres","Des souvenirs à ton image ♡"))

        val general=compactPanel("⚙  Général","Personnalise l’apparence et le fonctionnement de l’application")
        val generalBody=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL }
        val controls=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
        controls.addView(themeSelector())
        controls.addView(choiceLine("◎","Langue","Français") {
            AlertDialog.Builder(this@MainActivity).setTitle("Langue").setSingleChoiceItems(arrayOf("Français"),0,null).setPositiveButton("OK",null).show()
        })
        controls.addView(prefLine("●","Son et vibrations","","settings_sound",true))
        controls.addView(prefLine("★","Animations","Effets visuels et transitions","settings_animations",true))
        generalBody.addView(controls,LinearLayout.LayoutParams(0,-2,0.60f))
        generalBody.addView(scenicImage(R.drawable.ui_settings_scene,150),LinearLayout.LayoutParams(0,dp(150),0.40f).apply { marginStart=dp(6) })
        general.addView(generalBody)
        content.addView(general,LinearLayout.LayoutParams(-1,-2).apply {bottomMargin=dp(7)})

        val imports=compactPanel("▱  Import des albums existants","Ajoute tes photos et vidéos déjà présentes sur ton téléphone")
        val importRow=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL }
        importRow.addView(settingsButton("▧","Importer depuis la galerie","Sélectionne un ou plusieurs albums") {showAlbumImport()},LinearLayout.LayoutParams(0,dp(66),1f).apply{marginEnd=dp(3)})
        importRow.addView(settingsButton("⇩","Importer tous les albums","Analyse et propose un classement") {showAlbumImport(true)},LinearLayout.LayoutParams(0,dp(66),1f).apply{marginStart=dp(3)})
        imports.addView(importRow);content.addView(imports,LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=dp(7)})

        val two=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL }
        val auto=compactPanel("✦  Tri automatique","Laisse l’application classer tes fichiers")
        auto.addView(prefLine("✦","Activer le tri automatique","Analyse les nouveaux fichiers","auto_sort",false))
        auto.addView(prefLine("▱","Proposer une catégorie","Suggestion modifiable","auto_suggest",true))
        auto.addView(prefLine("＋","Créer une catégorie si besoin","Création automatique autorisée","auto_create",false))
        val types=compactPanel("Types de fichiers à analyser")
        types.addView(prefLine("▧","Images","","source_images",true))
        types.addView(prefLine("▶","Vidéos","","source_videos",true))
        types.addView(prefLine("▣","Captures d’écran","","source_screenshots",true))
        types.addView(prefLine("⇩","Téléchargements","","source_downloads",true))
        types.addView(prefLine("◉","Partages (WhatsApp, etc.)","","source_shares",true))
        types.addView(prefLine("▤","Autres","","source_other",false))
        two.addView(auto,LinearLayout.LayoutParams(0,-2,1f).apply{marginEnd=dp(3)})
        two.addView(types,LinearLayout.LayoutParams(0,-2,1f).apply{marginStart=dp(3)})
        content.addView(two,LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=dp(7)})

        val cats=compactPanel("▱  Gestion des catégories","Organise tes catégories selon tes préférences")
        val catRow=LinearLayout(this).apply {orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL}
        catRow.addView(settingsButton("⚙","Modifier les catégories","Photo, nom, icône et ordre") {showCategoryManager(false,CategoryEntry.SETTINGS)},LinearLayout.LayoutParams(0,dp(66),0.64f))
        catRow.addView(scenicImage(R.drawable.ui_category_scene,66),LinearLayout.LayoutParams(0,dp(66),0.36f).apply{marginStart=dp(6)})
        cats.addView(catRow);content.addView(cats,LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=dp(7)})

        val dup=compactPanel("▣  Gestion des doublons","Choisis l’action par défaut lors de la détection de doublons")
        val dupRow=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL}
        val left=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL}
        val duplicateMode=prefs.getString("duplicate_default","Me demander à chaque fois") ?: "Me demander à chaque fois"
        left.addView(choiceLine("","Action par défaut",duplicateMode){
            val opts=arrayOf("Me demander à chaque fois","Déplacer quand même","Déplacer vers Autres","Ignorer")
            AlertDialog.Builder(this@MainActivity).setTitle("Action par défaut").setItems(opts){_,i->prefs.edit().putString("duplicate_default",opts[i]).apply();showSettings()}.show()
        })
        left.addView(settingsButton("⌕","Analyser les doublons","Comparer les fichiers strictement identiques") {findDuplicates()},LinearLayout.LayoutParams(-1,dp(58)))
        val right=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(8),dp(4),dp(4),dp(4))
            addView(simpleLabel("Options disponibles",11f,gold));addView(simpleLabel("✓ Déplacer quand même",10f,cream));addView(simpleLabel("✓ Déplacer vers Autres",10f,cream));addView(simpleLabel("✓ Ignorer",10f,cream));addView(simpleLabel("✓ Appliquer ce choix aux autres doublons",9.5f,cream))}
        dupRow.addView(left,LinearLayout.LayoutParams(0,-2,0.55f));dupRow.addView(right,LinearLayout.LayoutParams(0,-2,0.45f))
        dup.addView(dupRow);content.addView(dup,LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=dp(7)})

        val bottom=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL}
        val storage=compactPanel("●  Espace de stockage","Voir l’espace utilisé et optimiser ton stockage")
        val stat=StatFs(Environment.getDataDirectory().path);val total=stat.totalBytes;val free=stat.availableBytes;val used=(total-free).coerceAtLeast(0L);val pct=if(total>0)((used*100)/total).toInt() else 0
        storage.addView(simpleLabel("${formatBytes(used)} utilisés sur ${formatBytes(total)}   ·   $pct %",10.5f,cream))
        val other=compactPanel("•••  Autres options")
        other.addView(compactLine("↻","Vider le cache","" ){AlertDialog.Builder(this@MainActivity).setTitle("Vider le cache ?").setMessage("Tes médias et classements ne seront pas supprimés.").setPositiveButton("Vider"){_,_->bitmapCache.evictAll();Toast.makeText(this@MainActivity,"Cache vidé",Toast.LENGTH_SHORT).show()}.setNegativeButton("Annuler",null).show()})
        other.addView(compactLine("⟲","Réinitialiser l’application","",{confirmReset()}))
        other.addView(compactLine("ⓘ","À propos","Version 0.6.18",null))
        bottom.addView(storage,LinearLayout.LayoutParams(0,-2,1f).apply{marginEnd=dp(3)});bottom.addView(other,LinearLayout.LayoutParams(0,-2,1f).apply{marginStart=dp(3)})
        content.addView(bottom,LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=dp(7)})
        content.addView(visualFooter(),LinearLayout.LayoutParams(-1,dp(125)))

        val scroll=ScrollView(this).apply{isFillViewport=true;addView(content)}
        val screen=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setBackgroundColor(black);addView(scroll,LinearLayout.LayoutParams(-1,0,1f));addView(navBar(Route.SETTINGS),LinearLayout.LayoutParams(-1,dp(65)))}
        setContentView(screen)
    }

    private fun exitCategoryManager() {
        when (categoryEntry) {
            CategoryEntry.SETTINGS -> showSettings()
            CategoryEntry.IMAGES -> showImagesCategories()
            CategoryEntry.VIDEOS -> showVideosCategories()
        }
    }

    /** Shared preferences, two filtered views; never duplicate a category by media type. */
    private fun showCategoryManager(video: Boolean, origin: CategoryEntry = categoryEntry) {
        generation++
        val request = generation
        categoryEntry = origin
        videoCategoryTab = video
        screenMode = "categories"
        route = Route.CATEGORIES
        selected.clear()
        val layout=root().apply { setPadding(dp(7),dp(3),dp(7),dp(6)) }
        layout.addView(brandHeader("Retour", "Mes catégories", { exitCategoryManager() }))
        layout.addView(simpleLabel("▱  Mes catégories",23f,gold))
        layout.addView(simpleLabel("Modifier la photo, le nom, l’icône et l’ordre",12f,cream))
        val tabs=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL }
        tabs.addView(if(!video) primaryAction("▧  Images") {showCategoryManager(false,origin)}
            else action("▧  Images") {showCategoryManager(false,origin)},
            LinearLayout.LayoutParams(0,dp(43),1f).apply {rightMargin=dp(3)})
        tabs.addView(if(video) primaryAction("▶  Vidéos") {showCategoryManager(true,origin)}
            else action("▶  Vidéos") {showCategoryManager(true,origin)},
            LinearLayout.LayoutParams(0,dp(43),1f).apply {leftMargin=dp(3)})
        layout.addView(tabs,LinearLayout.LayoutParams(-1,dp(43)).apply {topMargin=dp(6);bottomMargin=dp(7)})
        val names=categoriesForTab(video)
        val counts=linkedMapOf<String,TextView>()
        names.forEach { category ->
            val row=LinearLayout(this).apply {
                orientation=LinearLayout.HORIZONTAL
                gravity=Gravity.CENTER_VERTICAL
                setPadding(dp(3),dp(2),dp(6),dp(2))
                background=GradientDrawable().apply {
                    setColor(Color.rgb(18,18,17));cornerRadius=dp(9).toFloat()
                    setStroke(dp(1),Color.rgb(87,79,64))
                }
                isClickable=true;isFocusable=true
                contentDescription="Modifier la catégorie $category"
                setOnClickListener {showCategoryEditor(category)}
            }
            row.addView(currentCategoryPhoto(category).apply {
                background=GradientDrawable().apply {cornerRadius=dp(7).toFloat();setColor(black)}
                clipToOutline=true
            },LinearLayout.LayoutParams(dp(77),dp(49)))
            row.addView(simpleLabel(prefs.getString("category_icon_$category", "✦") ?: "✦",21f,gold),
                LinearLayout.LayoutParams(dp(31),dp(45)))
            val labels=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL}
            labels.addView(simpleLabel(category,13f,gold))
            labels.addView(simpleLabel("Modifier la catégorie",10f,cream))
            row.addView(labels,LinearLayout.LayoutParams(0,-2,1f))
            val count=simpleLabel("…",10f,cream)
            counts[category]=count
            row.addView(count,LinearLayout.LayoutParams(dp(28),-2))
            row.addView(simpleLabel("›",24f,gold),LinearLayout.LayoutParams(dp(16),dp(43)))
            layout.addView(row,LinearLayout.LayoutParams(-1,dp(54)).apply {bottomMargin=dp(3)})
        }
        if(video) layout.addView(action("▶  Autres vidéos · médias non classés") {openOtherVideos()},
            LinearLayout.LayoutParams(-1,dp(43)).apply {bottomMargin=dp(4)})
        layout.addView(primaryAction(if(video) "+  Ajouter une catégorie vidéo" else "+  Ajouter une catégorie") {
            addCategoryDialog()
        },LinearLayout.LayoutParams(-1,dp(48)).apply {topMargin=dp(4)})
        layout.addView(visualFooter(),LinearLayout.LayoutParams(-1,dp(100)))
        val page=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL;setBackgroundColor(black)}
        page.addView(ScrollView(this).apply {setBackgroundColor(black);addView(layout)},
            LinearLayout.LayoutParams(-1,0,1f))
        page.addView(navBar(Route.CATEGORIES),LinearLayout.LayoutParams(-1,dp(75)))
        setContentView(page)
        val target=if(video) Route.VIDEOS else Route.IMAGES
        if(hasPermission(target)) io.execute {
            val media=queryMedia(target)
            val grouped=media.groupingBy { getCategory(it) }.eachCount()
            runOnUiThread {
                if(generation==request && screenMode=="categories") counts.forEach { (category,label) ->
                    label.text="${grouped[category] ?: 0}"
                }
            }
        } else counts.values.forEach { it.text="—" }
    }

    private fun addCategoryDialog() {
        val editText = EditText(this).apply {
            hint = "Nom de la catégorie"
            setTextColor(cream); setHintTextColor(0xFFAAAAAA.toInt()); setSingleLine(true)
        }
        AlertDialog.Builder(this).setTitle("Nouvelle catégorie").setView(editText)
            .setPositiveButton("Ajouter") { _, _ ->
                val name = editText.text.toString().trim().replace("\u001f", "")
                if (name.isNotEmpty() && name.length <= 40 && !savedCategories().any { it.equals(name, true) }) {
                    prefs.edit()
                        .putString("category_names", (savedCategories() + name).joinToString("\u001f"))
                        .putString("category_scope_$name", if (videoCategoryTab) "video" else "image")
                        .apply()
                    showCategoryEditor(name)
                } else Toast.makeText(this, "Nom vide, trop long ou déjà utilisé", Toast.LENGTH_SHORT).show()
            }.setNegativeButton("Annuler", null).show()
    }

    private fun categoryArtwork(category: String): Int = R.drawable.art_edit_category

    private fun showCategoryEditor(category: String) {
        generation++
        screenMode = "editor"
        route = Route.CATEGORIES
        val layout = root().apply { setPadding(dp(8), dp(6), dp(8), dp(8)) }
        layout.addView(brandHeader("Mes catégories", "Modifier", {
            showCategoryManager(videoCategoryTab, categoryEntry)
        }))
        layout.addView(simpleLabel("⚙  Modifier une catégorie",22f,gold))
        layout.addView(simpleLabel("Personnalise ta catégorie selon tes envies",12f,cream))

        val identity = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity=Gravity.TOP }
        val photoPart = FrameLayout(this)
        photoPart.addView(currentCategoryPhoto(category), FrameLayout.LayoutParams(-1, dp(168)))
        photoPart.addView(action("▣  Changer l’image") {
            pendingCategoryImage = category
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                type = "image/*"; addCategory(Intent.CATEGORY_OPENABLE)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivityForResult(intent, 305)
        }, FrameLayout.LayoutParams(-1, dp(48), Gravity.BOTTOM))
        identity.addView(photoPart, LinearLayout.LayoutParams(0, dp(168), 0.41f).apply { rightMargin=dp(5) })
        val fields = section("Nom de la catégorie")
        val name = EditText(this).apply {
            setText(category); setTextColor(cream); setSingleLine(true); textSize=15f
            hint = "Nom"; setHintTextColor(0xFFAAAAAA.toInt())
            background = GradientDrawable().apply {
                setColor(Color.rgb(15,14,12)); cornerRadius=dp(9).toFloat(); setStroke(dp(1),gold)
            }
            setPadding(dp(8), dp(4), dp(8), dp(4))
        }
        fields.addView(name, LinearLayout.LayoutParams(-1, dp(48)))
        val iconLabel = simpleLabel("Icônes proposées", 12f, gold)
        fields.addView(iconLabel)
        val icons = listOf("⚒", "♟", "❧", "▣", "⚑", "★", "+")
        var pickedIcon = prefs.getString("category_icon_$category", "⚒") ?: "⚒"
        val chosen = simpleLabel("Icône choisie : $pickedIcon", 12f, cream)
        var iconPreview: TextView? = null
        val iconPicker = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled=false }
        val iconRow = LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL }
        icons.forEach { symbol ->
            iconRow.addView(action(symbol) {
                pickedIcon=symbol
                chosen.text="Icône choisie : $pickedIcon"
                iconPreview?.let { it.text="$pickedIcon  ${name.text}" }
            },
                LinearLayout.LayoutParams(dp(45), dp(45)).apply { rightMargin=dp(3) })
        }
        iconPicker.addView(iconRow)
        fields.addView(iconPicker)
        fields.addView(chosen)
        identity.addView(fields, LinearLayout.LayoutParams(0,-2,0.59f).apply { leftMargin=dp(3) })
        layout.addView(identity, LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=dp(9) })

        val count = allKnownMediaKeysForCategory(category)
        val preview = section("Prévisualisation")
        val previewRow = LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER_VERTICAL }
        previewRow.addView(ImageView(this).apply {
            val customImage = prefs.getString("category_image_$category", null)?.let { File(it) }
            if (customImage != null && customImage.exists()) setImageBitmap(BitmapFactory.decodeFile(customImage.absolutePath))
            else setImageBitmap(questionMarkBitmap())
            scaleType=ImageView.ScaleType.CENTER_CROP
            contentDescription="Prévisualisation de $category"
        }, LinearLayout.LayoutParams(0,dp(100),0.53f))
        val previewText=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
        val liveName=simpleLabel("$pickedIcon  $category", 19f, cream)
        iconPreview=liveName
        previewText.addView(liveName)
        previewText.addView(simpleLabel("$count média(s) classé(s)",12f))
        previewRow.addView(previewText,LinearLayout.LayoutParams(0,-2,0.47f))
        preview.addView(previewRow)
        layout.addView(preview,LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=dp(9) })
        name.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                liveName.text="${pickedIcon}  ${s?.toString() ?: category}"
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })
        layout.addView(primaryAction("✓  Enregistrer les modifications") {
            val newName = name.text.toString().trim().replace("\u001f", "")
            if (newName.isBlank() || newName.length > 40 ||
                savedCategories().any { it != category && it.equals(newName,true) }) {
                Toast.makeText(this, "Nom vide, trop long ou déjà utilisé", Toast.LENGTH_LONG).show()
                return@primaryAction
            }
            val cats = savedCategories().map { if (it == category) newName else it }
            val e = prefs.edit().putString("category_names", cats.distinct().joinToString("\u001f"))
            prefs.all.filterValues { it == category }.keys.filter { it.startsWith("media_") }
                .forEach { e.putString(it, newName) }
            prefs.getString("category_image_$category", null)?.let {
                e.putString("category_image_$newName",it)
                if (newName!=category) e.remove("category_image_$category")
            }
            if (newName!=category) {
                e.remove("category_icon_$category")
                e.remove("category_scope_$category")
                e.putString("category_alias_$category",newName)
                e.remove("category_video_$category")
                e.putBoolean("category_video_$newName",
                    category in videoCatalogCategories || prefs.getBoolean("category_video_$category",false))
                prefs.all.filterValues { it == category }.keys
                    .filter { it.startsWith("category_alias_") }
                    .forEach { e.putString(it,newName) }
            }
            e.putString("category_scope_$newName",categoryScope(category))
            e.putString("category_icon_$newName",pickedIcon)
            e.apply()
            Toast.makeText(this,"Catégorie enregistrée",Toast.LENGTH_SHORT).show()
            showCategoryManager(videoCategoryTab, categoryEntry)
        },LinearLayout.LayoutParams(-1,dp(58)).apply { bottomMargin=dp(9) })

        val org = section("Organisation")
        org.addView(rowCard("⇅","Déplacer cette catégorie","Choisir sa position dans la liste") {
            val names=categoriesForTab(videoCategoryTab)
            AlertDialog.Builder(this).setTitle("Placer « $category » avant…")
                .setItems(names.mapIndexed { index, n -> "${index+1}. $n" }.toTypedArray()) { _, idx ->
                    val ordered=savedCategories().toMutableList()
                    if (ordered.remove(category)) {
                        val target=names[idx]
                        val insertion=if (target == category)
                            savedCategories().indexOf(category).coerceAtMost(ordered.size)
                            else ordered.indexOf(target).coerceAtLeast(0)
                        ordered.add(insertion,category)
                        prefs.edit().putString("category_names",ordered.joinToString("\u001f")).apply()
                        Toast.makeText(this,"Position enregistrée",Toast.LENGTH_SHORT).show()
                        showCategoryManager(videoCategoryTab,categoryEntry)
                    }
                }.setNegativeButton("Annuler",null).show()
        })
        layout.addView(org,LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=dp(9) })
        val statistics=section("Statistiques")
        statistics.addView(rowCard("◴","Contenu de la catégorie","$count média(s) classé(s)") {
            AlertDialog.Builder(this).setTitle("Voir les médias de « $category »")
                .setItems(arrayOf("Images", "Vidéos")) { _, choice ->
                    pendingCategory=category
                    navigate(if(choice==0) Route.IMAGES else Route.VIDEOS)
                }.setNegativeButton("Annuler",null).show()
        })
        layout.addView(statistics,LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=dp(9) })
        val danger=section("Supprimer la catégorie",
            "Choisis ce qu’il faut faire des fichiers. Conserver les originaux est sélectionné par défaut.")
        danger.background=GradientDrawable().apply {
            setColor(Color.rgb(43,11,10)); cornerRadius=dp(14).toFloat()
            setStroke(dp(1),Color.rgb(236,71,64))
        }
        val choice=RadioGroup(this).apply { orientation=RadioGroup.VERTICAL }
        val keep=RadioButton(this).apply {
            id=View.generateViewId()
            text="Conserver les fichiers · retirer uniquement la catégorie"
            setTextColor(cream);textSize=13f;isChecked=true
        }
        val trash=RadioButton(this).apply {
            id=View.generateViewId()
            text="Mettre aussi les fichiers à la corbeille Android (500 max)"
            setTextColor(cream);textSize=12f
            isEnabled=Build.VERSION.SDK_INT >= 30
        }
        choice.addView(keep)
        choice.addView(trash)
        choice.check(keep.id)
        danger.addView(choice)
        danger.addView(dangerAction("▣  Supprimer la catégorie") {
            if (choice.checkedRadioButtonId == trash.id) {
                deleteCategoryAndTrash(category)
            } else {
                AlertDialog.Builder(this).setTitle("Supprimer « $category » ?")
                    .setMessage("Le classement de cette catégorie sera retiré. Aucun fichier photo ou vidéo original ne sera supprimé.")
                    .setPositiveButton("Continuer") { _, _ ->
                        AlertDialog.Builder(this).setTitle("Dernière confirmation")
                            .setMessage("Confirmer la suppression de la catégorie « $category » en conservant tous les fichiers ?")
                            .setPositiveButton("Oui, supprimer la catégorie") { _, _ ->
                                deleteCategoryMetadata(category)
                            }.setNegativeButton("Annuler",null).show()
                    }.setNegativeButton("Annuler",null).show()
            }
        },LinearLayout.LayoutParams(-1,dp(56)))
        layout.addView(danger)
        layout.addView(visualFooter(),LinearLayout.LayoutParams(-1,dp(145)))
        setContentView(ScrollView(this).apply { setBackgroundColor(black);addView(layout) })
    }

    private fun deleteCategoryMetadata(category: String) {
        prefs.getString("category_image_$category", null)?.let { path ->
            try { File(path).takeIf { it.exists() && it.parentFile == File(filesDir,"category_images") }?.delete() } catch (_:Exception) {}
        }
        val edit = prefs.edit().putString("category_names",
            savedCategories().filterNot { it == category }.joinToString("\u001f"))
        prefs.all.filterValues { it == category }.keys.filter { it.startsWith("media_") }
            .forEach { edit.remove(it) }
        edit.remove("category_image_$category")
            .remove("category_icon_$category").remove("category_scope_$category")
            .remove("category_video_$category")
        prefs.all.filterValues { it == category }.keys.filter { it.startsWith("category_alias_") }
            .forEach { edit.remove(it) }
        edit.apply()
        showCategoryManager(videoCategoryTab,categoryEntry)
    }

    /** Deleting source media needs two app confirmations AND Android's trash permission. */
    private fun deleteCategoryAndTrash(category: String) {
        if (Build.VERSION.SDK_INT < 30 || !hasPermission(Route.IMAGES) ||
            !hasPermission(Route.VIDEOS) ||
            (Build.VERSION.SDK_INT >= 33 && (!allowed(Manifest.permission.READ_MEDIA_IMAGES) ||
                !allowed(Manifest.permission.READ_MEDIA_VIDEO)))) {
            AlertDialog.Builder(this).setTitle("Accès complet nécessaire")
                .setMessage("Pour inclure les fichiers originaux, autorise d’abord l’accès complet aux images et vidéos. Tu peux toujours supprimer seulement la catégorie sans toucher aux fichiers.")
                .setPositiveButton("Compris",null).show()
            return
        }
        val request=++generation
        Toast.makeText(this,"Vérification des fichiers de la catégorie…",Toast.LENGTH_SHORT).show()
        io.execute {
            val medias=(queryMedia(Route.IMAGES)+queryMedia(Route.VIDEOS))
                .filter { getCategory(it)==category }
                .distinctBy { it.key }
            runOnUiThread {
                if (request!=generation || screenMode!="editor") return@runOnUiThread
                when {
                    medias.size>500 -> AlertDialog.Builder(this).setTitle("500 fichiers maximum")
                        .setMessage("Cette catégorie contient ${medias.size} fichiers. Classe-les en lots de 500 avant de demander la corbeille.")
                        .setPositiveButton("Compris",null).show()
                    medias.isEmpty() -> AlertDialog.Builder(this).setTitle("Aucun fichier accessible")
                        .setMessage("Aucun original accessible pour cette catégorie. Tu peux retirer uniquement son classement.")
                        .setPositiveButton("Compris",null).show()
                    else -> AlertDialog.Builder(this).setTitle("Fichiers originaux : ${medias.size}")
                        .setMessage("La catégorie sera supprimée uniquement si Android confirme la mise à la corbeille de ces ${medias.size} fichiers. Continuer ?")
                        .setPositiveButton("Vérifier une dernière fois") { _,_ ->
                            confirmTrashFinal(medias,category)
                        }.setNegativeButton("Annuler",null).show()
                }
            }
        }
    }

    private fun allKnownMediaKeysForCategory(category: String): Int =
        prefs.all.count { (k, v) -> k.startsWith("media_") && v == category }

    private fun showAlbumImport(preselectAll: Boolean = false) {
        screenMode = "import"
        if (!hasPermission(Route.UNSORTED)) {
            Toast.makeText(this,"Autorise d’abord l’accès aux photos et vidéos.",Toast.LENGTH_LONG).show()
            navigate(Route.UNSORTED)
            return
        }
        val request = ++generation
        val layout=root().apply { setPadding(dp(8),dp(6),dp(8),dp(8)) }
        layout.addView(brandHeader("Retour","Import des albums",{showSettings()}))
        val heroRow=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL }
        heroRow.addView(scenicImage(R.drawable.ui_import_rabbits_scene,160),
            LinearLayout.LayoutParams(0,dp(160),0.42f))
        val intro=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
        intro.addView(heading("IMPORT DE VOS ALBUMS",19f))
        intro.addView(note("Albums détectés sur ton appareil. Choisis manuellement leur catégorie ; les originaux restent en place."))
        heroRow.addView(intro,LinearLayout.LayoutParams(0,-2,0.58f))
        layout.addView(heroRow,LinearLayout.LayoutParams(-1,dp(160)))
        val info=note("Recherche des albums non classés…")
        layout.addView(info)
        val albumList=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
        layout.addView(albumList)
        setContentView(ScrollView(this).apply { setBackgroundColor(black);addView(layout) })
        io.execute {
            val all=queryMedia(Route.UNSORTED)
            val groups=all.groupBy { it.album }.toSortedMap()
            runOnUiThread {
                if (generation!=request || screenMode!="import") return@runOnUiThread
                info.text="${groups.size} album(s) · ${all.size} média(s) non classé(s)"
                val checked=linkedSetOf<String>()
                val destinations=linkedMapOf<String,String>()
                val summary=simpleLabel("Aucun album sélectionné",14f,gold)
                fun updateSummary() {
                    val count=checked.sumOf { groups[it]?.size ?: 0 }
                    summary.text="${checked.size} album(s) sélectionné(s) · $count média(s)"
                }
                val headingBox=section("SÉLECTION DES ALBUMS",
                    "Albums réellement détectés. Choisis chaque destination avant de classer.")
                albumList.addView(headingBox,LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=dp(8) })
                val albumChecks=linkedMapOf<String,CheckBox>()
                albumList.addView(action("☑  Tout sélectionner / désélectionner") {
                    val newState=checked.size!=groups.size
                    albumChecks.values.forEach { it.isChecked=newState }
                },LinearLayout.LayoutParams(-1,dp(49)).apply { bottomMargin=dp(7) })
                groups.forEach { (album,medias) ->
                    val photos=medias.count { it.mime.startsWith("image/") }
                    val card=section(album,"${medias.size} éléments · $photos photos · ${medias.size-photos} vidéos")
                    val row=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL }
                    val checkbox=CheckBox(this).apply {
                        setTextColor(cream);isChecked=false
                        setOnCheckedChangeListener { _,v ->
                            if(v) checked.add(album) else checked.remove(album)
                            updateSummary()
                        }
                    }
                    albumChecks[album]=checkbox
                    row.addView(checkbox,LinearLayout.LayoutParams(dp(42),dp(57)))
                    row.addView(mediaArtwork(medias.first(),65),LinearLayout.LayoutParams(dp(63),dp(65)))
                    val destination=simpleLabel("Destination : à choisir",12f,gold)
                    row.addView(destination,LinearLayout.LayoutParams(0,-2,1f).apply { leftMargin=dp(8) })
                    card.addView(row)
                    card.addView(mediaPreviewStrip(medias),LinearLayout.LayoutParams(-1,dp(75)))
                    card.addView(action("▣  Choisir la catégorie  ›") {
                        val cats=savedCategories()
                        AlertDialog.Builder(this).setTitle("Classer « $album » dans…")
                            .setItems(cats.toTypedArray()) { _,idx ->
                                destinations[album]=cats[idx]
                                destination.text="Vers : ${cats[idx]}"
                                checkbox.isChecked=true
                            }.setNegativeButton("Annuler",null).show()
                    },LinearLayout.LayoutParams(-1,dp(51)))
                    albumList.addView(card,LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=dp(7) })
                }
                if (preselectAll) albumChecks.values.forEach { it.isChecked=true }
                val footer=section("RÉCAPITULATIF")
                footer.addView(summary)
                footer.addView(primaryAction("⇩  Classer les albums sélectionnés") {
                    val missing=checked.filter { destinations[it].isNullOrBlank() }
                    val chosen=checked.flatMap { groups[it] ?: emptyList() }
                    when {
                        checked.isEmpty() -> Toast.makeText(this,"Sélectionne au moins un album",Toast.LENGTH_LONG).show()
                        missing.isNotEmpty() -> AlertDialog.Builder(this).setTitle("Catégories manquantes")
                            .setMessage("Choisis la destination de : ${missing.joinToString()}")
                            .setPositiveButton("Compris",null).show()
                        chosen.size>1000 -> AlertDialog.Builder(this).setTitle("1 000 éléments maximum")
                            .setMessage("${chosen.size} fichiers sélectionnés. Réduis la sélection pour respecter la limite.")
                            .setPositiveButton("Compris",null).show()
                        else -> AlertDialog.Builder(this).setTitle("Valider le classement ?")
                            .setMessage("${chosen.size} média(s) dans ${checked.size} album(s). Seul le classement Lapibreizh sera modifié, aucun fichier physique déplacé ni copié.")
                            .setPositiveButton("Valider") { _,_->
                                val selectedAlbums=checked.toList()
                                val mapping=destinations.toMap()
                                Toast.makeText(this,"Enregistrement du classement…",Toast.LENGTH_SHORT).show()
                                io.execute {
                                    val committed=try {
                                        val editor=prefs.edit()
                                        selectedAlbums.forEach { album ->
                                            groups[album]?.forEach { media ->
                                                editor.putString("media_${media.key}",mapping.getValue(album))
                                            }
                                        }
                                        editor.commit()
                                    } catch (_:Exception) { false }
                                    runOnUiThread {
                                        if (committed) {
                                            Toast.makeText(this,"${chosen.size} médias classés",Toast.LENGTH_LONG).show()
                                            showSettings()
                                        } else AlertDialog.Builder(this)
                                            .setTitle("Classement non confirmé")
                                            .setMessage("L’enregistrement a échoué. Vérifie le stockage disponible et réessaie.")
                                            .setPositiveButton("Compris",null).show()
                                    }
                                }
                            }.setNegativeButton("Annuler",null).show()
                    }
                },LinearLayout.LayoutParams(-1,dp(65)))
                albumList.addView(footer)
                albumList.addView(section("ℹ  ORIGINAUX PRÉSERVÉS",
                    "Cette opération n’effectue aucune copie ni aucun déplacement physique."))
                albumList.addView(visualFooter(),LinearLayout.LayoutParams(-1,dp(145)))
            }
        }
    }

    private fun mediaArtwork(media: Media, h: Int): ImageView {
        val image = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(this@MainActivity.background)
            contentDescription = media.title
            tag = media.key
        }
        val cached = bitmapCache.get(media.key)
        if (cached != null) image.setImageBitmap(cached)
        else io.execute {
            val bmp = try { thumbnail(media) } catch (_: Exception) { null }
            if (bmp != null) {
                bitmapCache.put(media.key,bmp)
                image.post { if (image.tag == media.key) image.setImageBitmap(bmp) }
            }
        }
        return image
    }

    private fun mediaPreviewStrip(medias: List<Media>): LinearLayout =
        LinearLayout(this).apply {
            orientation=LinearLayout.HORIZONTAL
            medias.take(4).forEach { media ->
                addView(mediaArtwork(media,85),LinearLayout.LayoutParams(0,dp(85),1f).apply {
                    marginStart=dp(2); marginEnd=dp(2)
                })
            }
            if (medias.size > 4) addView(note("+${medias.size-4}"),
                LinearLayout.LayoutParams(0,dp(85),1f))
        }

    private fun showMoveSelected() {
        val medias = galleryItems.filter { selected.contains(it.key) }
        if (medias.isEmpty()) return
        if (medias.size > 1000) {
            AlertDialog.Builder(this).setTitle("1 000 éléments maximum")
                .setMessage("Réduis la sélection avant de lancer ce classement.")
                .setPositiveButton("Compris",null).show()
            return
        }
        val cats = savedCategories()
        AlertDialog.Builder(this).setTitle("Déplacer / classer ${medias.size} média(s)")
            .setMessage("Choisis la catégorie de destination. Les fichiers originaux ne seront pas déplacés.")
            .setItems(cats.toTypedArray()) { _, index ->
                showMoveProgress(medias, cats[index])
            }.setNegativeButton("Annuler", null).show()
    }

    private fun showMoveProgress(medias: List<Media>, destination: String) {
        val request = ++generation
        screenMode = "progress"
        val layout = root()
        layout.addView(brandHeader("Galerie", "Médiathèque", { loadGallery() }))
        layout.addView(visualTitleArt(R.drawable.ui_move_glyph, "CLASSEMENT EN COURS",
            "Les originaux restent à leur emplacement. Les compteurs viennent des écritures réellement effectuées."))
        val progressCard = section("PROGRESSION")
        val progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = medias.size
        }
        val txt = heading("0 / ${medias.size} fichiers",20f)
        progressCard.addView(progress,LinearLayout.LayoutParams(-1,dp(24)))
        val percent=simpleLabel("0 %",20f,gold)
        progressCard.addView(percent)
        progressCard.addView(txt)
        val origins=medias.map { getCategory(it).ifEmpty { "Non classées" } }.distinct()
        progressCard.addView(note("Depuis : ${if (origins.size==1) origins.first() else "Plusieurs catégories"}  →  Vers : $destination"))
        layout.addView(progressCard,LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=dp(9) })
        layout.addView(mediaPreviewStrip(medias),LinearLayout.LayoutParams(-1,dp(94)))
        layout.addView(section("ℹ  CLASSEMENT LOGIQUE",
            "Une catégorie Lapibreizh est enregistrée pour chaque média. Aucun fichier physique n’est déplacé."),
            LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(8) })
        layout.addView(visualFooter(),LinearLayout.LayoutParams(-1,dp(142)))
        setContentView(ScrollView(this).apply { setBackgroundColor(black);addView(layout) })
        io.execute {
            var changed=0
            var already=0
            var errors=0
            var processed=0
            medias.chunked(25).forEach { chunk ->
                val edits=chunk.filter { getCategory(it)!=destination }
                already+=chunk.size-edits.size
                val saved=if (edits.isEmpty()) true else try {
                    val editor=prefs.edit()
                    edits.forEach { editor.putString("media_${it.key}",destination) }
                    editor.commit()
                } catch (_:Exception) { false }
                if (saved) changed+=edits.size else errors+=edits.size
                processed+=chunk.size
                val done=processed
                runOnUiThread {
                    if (request == generation && screenMode=="progress") {
                        progress.progress=done
                        percent.text="${done * 100 / medias.size} %"
                        txt.text="$done / ${medias.size} fichiers"
                    }
                }
            }
            runOnUiThread {
                if (request == generation && screenMode=="progress")
                    showMoveResult(changed,already,errors,processed,destination)
            }
        }
    }

    private fun showMoveResult(changed: Int, already: Int, errors: Int,
                               processed: Int, destination: String) {
        screenMode = "result"
        selected.clear()
        val layout=root()
        layout.addView(brandHeader("Galerie","Médiathèque",{ loadGallery() }))
        val title=if(errors==0) "CLASSEMENT TERMINÉ !" else "CLASSEMENT PARTIEL"
        layout.addView(visualTitleArt(R.drawable.ui_move_glyph,title,
            "$processed fichier(s) examiné(s) · originaux inchangés"))
        val results=section("BILAN RÉEL", "Destination : $destination")
        results.background=GradientDrawable().apply {
            setColor(Color.rgb(if(errors==0) 12 else 43,if(errors==0) 40 else 29,20))
            cornerRadius=dp(15).toFloat()
            setStroke(dp(1),if(errors==0) Color.rgb(64,185,104) else gold)
        }
        results.addView(simpleLabel("✓  $changed nouveau(x) classement(s) enregistré(s)",16f,Color.rgb(110,233,146)))
        results.addView(simpleLabel("▣  $already déjà dans la catégorie",14f,gold))
        results.addView(simpleLabel("!  $errors écriture(s) non confirmée(s)",14f,
            if(errors>0) Color.rgb(255,121,105) else cream))
        results.addView(simpleLabel("Total : $processed traité(s)",14f,cream))
        layout.addView(results,LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=dp(10) })
        layout.addView(primaryAction("▣  Voir les fichiers classés") {
            AlertDialog.Builder(this).setTitle("Ouvrir « $destination »")
                .setItems(arrayOf("Images","Vidéos")) { _,choice ->
                    pendingCategory=destination
                    navigate(if(choice==0) Route.IMAGES else Route.VIDEOS)
                }.setNegativeButton("Annuler",null).show()
        },LinearLayout.LayoutParams(-1,dp(57)).apply { bottomMargin=dp(7) })
        layout.addView(action("⌂  Retour à la médiathèque") { showMediaDashboard(Route.IMAGES) },
            LinearLayout.LayoutParams(-1,dp(57)))
        layout.addView(visualFooter(),LinearLayout.LayoutParams(-1,dp(149)))
        setContentView(ScrollView(this).apply { setBackgroundColor(black);addView(layout) })
    }

    private fun showDeleteSelected() {
        screenMode = "delete"
        val medias = galleryItems.filter { selected.contains(it.key) }
        if (medias.isEmpty()) return
        if (medias.size > 500) {
            AlertDialog.Builder(this).setTitle("500 médias maximum")
                .setMessage("Tu as sélectionné ${medias.size} médias. La suppression est limitée à 500 médias par opération.")
                .setPositiveButton("Compris", null).show()
            return
        }
        val total = medias.sumOf { it.size }
        val layout = root()
        layout.addView(brandHeader("Galerie", "Médiathèque", { loadGallery() }))
        layout.addView(visualTitleArt(R.drawable.ui_delete_glyph, "SUPPRIMER DES MÉDIAS",
            "Vérifie soigneusement ta sélection avant de continuer."))
        val selectionCard = section("✓  ${medias.size} élément(s) sélectionné(s)",
            "Sur 500 maximum · Taille totale : ${formatBytes(total)}")
        selectionCard.addView(mediaPreviewStrip(medias))
        selectionCard.addView(simpleLabel(
            medias.take(4).joinToString("  ·  ") { it.title } +
                if (medias.size > 4) "  ·  +${medias.size-4} autres" else "", 12f, cream))
        layout.addView(selectionCard, LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=dp(10) })
        val warning = LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL; setPadding(dp(14),dp(12),dp(14),dp(12))
            background=GradientDrawable().apply { setColor(Color.rgb(65,12,10)); cornerRadius=dp(14).toFloat(); setStroke(dp(1),Color.rgb(225,58,52)) }
            addView(heading("⚠  ATTENTION",18f))
            addView(note(if (Build.VERSION.SDK_INT >= 30)
                "Après ta confirmation, Android affichera encore sa propre confirmation avant la mise à la corbeille."
                else "Sur cette version d’Android, la suppression peut être définitive."))
        }
        layout.addView(warning, LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=dp(10) })
        val confirmed = CheckBox(this).apply {
            text = "Je confirme le traitement de ces ${medias.size} fichiers"
            textSize=15f; setTextColor(cream); setPadding(dp(8),dp(8),dp(8),dp(8))
        }
        layout.addView(confirmed)
        layout.addView(dangerAction(if (Build.VERSION.SDK_INT >= 30) "▣  Mettre à la corbeille" else "▣  Supprimer définitivement") {
            if (confirmed.isChecked) confirmTrashFinal(medias)
            else Toast.makeText(this,"Coche d’abord la case de confirmation",Toast.LENGTH_SHORT).show()
        }, LinearLayout.LayoutParams(-1, dp(62)).apply { bottomMargin=dp(8) })
        layout.addView(action("✕  Annuler · conserver les éléments") { loadGallery() }, LinearLayout.LayoutParams(-1,dp(58)))
        layout.addView(visualFooter(), LinearLayout.LayoutParams(-1,dp(145)))
        setContentView(ScrollView(this).apply { setBackgroundColor(black); addView(layout) })
    }

    private fun confirmTrashFinal(medias: List<Media>, categoryToDelete: String? = null) {
        if (medias.size > 500) {
            AlertDialog.Builder(this)
                .setTitle("500 médias maximum")
                .setMessage("Cette opération contient ${medias.size} médias. Réduis la sélection à 500 maximum.")
                .setPositiveButton("Compris", null)
                .show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Dernière confirmation")
            .setMessage("Confirmer la mise à la corbeille de ${medias.size} média(s) ?")
            .setPositiveButton("Oui, continuer") { _, _ ->
                pendingCategoryDeletion = categoryToDelete
                requestTrash(medias)
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun requestTrash(medias: List<Media>) {
        pendingTrashKeys = medias.map { it.key }
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                val request = MediaStore.createTrashRequest(contentResolver, medias.map { it.uri }, true)
                startIntentSenderForResult(request.intentSender, 304, null, 0, 0, 0)
            } catch (_: Exception) {
                pendingCategoryDeletion=null
                pendingTrashKeys=emptyList()
                Toast.makeText(this, "Impossible d’ouvrir la corbeille Android.", Toast.LENGTH_LONG).show()
            }
        } else {
            var deleted = 0
            medias.forEach { try { deleted += contentResolver.delete(it.uri, null, null) } catch (_: Exception) {} }
            selected.clear()
            Toast.makeText(this, "$deleted média(s) supprimé(s)", Toast.LENGTH_LONG).show()
            loadGallery()
        }
    }

    @Deprecated("Legacy result callback used for Android media trash confirmation")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 304) {
            val categoryToDelete=pendingCategoryDeletion
            pendingCategoryDeletion=null
            if (resultCode == RESULT_OK) {
                selected.removeAll(pendingTrashKeys.toSet())
                duplicateTrashSelection.clear()
                Toast.makeText(this,"Médias placés dans la corbeille",Toast.LENGTH_LONG).show()
            }
            pendingTrashKeys=emptyList()
            if (categoryToDelete!=null) {
                if (resultCode==RESULT_OK) deleteCategoryMetadata(categoryToDelete)
                else showCategoryEditor(categoryToDelete)
                return
            }
            if (route == Route.SETTINGS) showSettings() else loadGallery()
        } else if (requestCode == 305 && resultCode == RESULT_OK) {
            val category = pendingCategoryImage
            val uri = data?.data
            if (category != null && uri != null) {
                showCropper(category, uri)
            }
        } else if (requestCode == 305) {
            pendingCategoryImage=null
        }
    }

    /** Cadrage local : l'original Galerie n'est jamais modifié. */
    private fun showCropper(category: String, uri: Uri) {
        val bitmap = try {
            contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
        } catch (_:Exception) { null }
        if (bitmap == null) {
            pendingCategoryImage=null
            Toast.makeText(this,"Impossible de lire cette image",Toast.LENGTH_LONG).show()
            showCategoryEditor(category)
            return
        }
        screenMode="crop-category"
        val layout=root()
        layout.addView(heading("CADRER LA MINIATURE",22f))
        layout.addView(note("Déplace l’image avec un doigt et pince avec deux doigts pour zoomer."))
        val cropView=CategoryCropView(this,bitmap)
        layout.addView(cropView,LinearLayout.LayoutParams(-1,dp(360)))
        layout.addView(primaryAction("✓  Utiliser cette image") {
            val cropped=cropView.exportCropped(960,600)
            if (cropped == null) {
                Toast.makeText(this,"Le cadrage n’a pas pu être enregistré",Toast.LENGTH_LONG).show()
                return@primaryAction
            }
            val dir=File(filesDir,"category_images").apply { mkdirs() }
            val target=File(dir,"category_${System.currentTimeMillis()}.jpg")
            val ok=try { FileOutputStream(target).use { cropped.compress(Bitmap.CompressFormat.JPEG,90,it) } } catch (_:Exception) { false }
            if (!ok || !target.exists() || target.length()==0L) {
                target.delete(); Toast.makeText(this,"Échec de la copie interne",Toast.LENGTH_LONG).show(); return@primaryAction
            }
            val oldPath=prefs.getString("category_image_$category",null)
            prefs.edit().putString("category_image_$category",target.absolutePath).apply()
            if (!oldPath.isNullOrBlank() && oldPath != target.absolutePath) {
                try { File(oldPath).takeIf { it.exists() && it.parentFile == dir }?.delete() } catch (_:Exception) {}
            }
            pendingCategoryImage=null
            Toast.makeText(this,"Miniature enregistrée · original conservé",Toast.LENGTH_SHORT).show()
            showCategoryEditor(category)
        },LinearLayout.LayoutParams(-1,dp(58)).apply { topMargin=dp(10) })
        layout.addView(action("Annuler") { pendingCategoryImage=null; showCategoryEditor(category) },LinearLayout.LayoutParams(-1,dp(54)).apply { topMargin=dp(6) })
        setContentView(ScrollView(this).apply { setBackgroundColor(black); addView(layout) })
    }

    private inner class CategoryCropView(context: android.content.Context, private val source: Bitmap) : View(context) {
        private val paint=Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private val matrix=Matrix()
        private var scale=1f
        private var dx=0f
        private var dy=0f
        private var lastX=0f
        private var lastY=0f
        private val detector=ScaleGestureDetector(context,object:ScaleGestureDetector.SimpleOnScaleGestureListener(){
            override fun onScale(d:ScaleGestureDetector):Boolean {
                scale=(scale*d.scaleFactor).coerceIn(1f,6f); invalidate(); return true
            }
        })
        override fun onTouchEvent(e:MotionEvent):Boolean {
            detector.onTouchEvent(e)
            when(e.actionMasked){
                MotionEvent.ACTION_DOWN -> { lastX=e.x; lastY=e.y }
                MotionEvent.ACTION_MOVE -> if(!detector.isInProgress){ dx+=e.x-lastX; dy+=e.y-lastY; lastX=e.x; lastY=e.y; invalidate() }
            }
            return true
        }
        private fun drawMatrix(outW:Int,outH:Int):Matrix {
            val base=maxOf(outW.toFloat()/source.width,outH.toFloat()/source.height)
            val actual=base*scale
            val sw=source.width*actual; val sh=source.height*actual
            val maxDx=maxOf(0f,(sw-outW)/2f); val maxDy=maxOf(0f,(sh-outH)/2f)
            val x=dx.coerceIn(-maxDx,maxDx); val y=dy.coerceIn(-maxDy,maxDy)
            return Matrix().apply { postScale(actual,actual); postTranslate((outW-sw)/2f+x,(outH-sh)/2f+y) }
        }
        override fun onDraw(c:Canvas){ super.onDraw(c); c.drawColor(Color.rgb(10,9,8)); c.drawBitmap(source,drawMatrix(width,height),paint) }
        fun exportCropped(outW:Int,outH:Int):Bitmap? = try {
            Bitmap.createBitmap(outW,outH,Bitmap.Config.ARGB_8888).also { Canvas(it).drawBitmap(source,drawMatrix(outW,outH),paint) }
        } catch (_:Exception){ null }
    }

    private fun findDuplicates() {
        if (!hasPermission(Route.UNSORTED)) {
            Toast.makeText(this, "Autorise d’abord l’accès aux médias.", Toast.LENGTH_LONG).show()
            navigate(Route.UNSORTED)
            return
        }
        val layout = root()
        layout.addView(action("‹ Annuler") { showSettings() })
        layout.addView(heading("RECHERCHE DES DOUBLONS"))
        val info = note("Comparaison SHA-256 en cours…")
        layout.addView(info)
        setContentView(layout)
        io.execute {
            val all = (queryMedia(Route.IMAGES) + queryMedia(Route.VIDEOS))
                .groupBy { it.size }.values.filter { it.size > 1 }.flatten()
            val groups = linkedMapOf<String, MutableList<Media>>()
            all.forEachIndexed { index, media ->
                val hash = sha256(media)
                if (hash != null) groups.getOrPut(hash) { mutableListOf() }.add(media)
                if (index % 10 == 0) runOnUiThread { info.text = "${index + 1} / ${all.size} médias analysés…" }
            }
            val dupes = groups.values.filter { it.size > 1 }
            duplicatePairs = dupes.flatMap { group -> group.drop(1).map { group.first() to it } }
            duplicateIndex = 0
            duplicateTrashSelection.clear()
            runOnUiThread {
                if (duplicatePairs.isEmpty()) {
                    AlertDialog.Builder(this).setTitle("Doublons")
                        .setMessage("Aucun doublon strictement identique trouvé.")
                        .setPositiveButton("OK") { _, _ -> showSettings() }.show()
                } else showDuplicateComparison()
            }
        }
    }

    private fun sha256(media: Media): String? {
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            contentResolver.openInputStream(media.uri)?.use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n <= 0) break
                    digest.update(buffer, 0, n)
                }
            } ?: return null
            digest.digest().joinToString("") { "%02x".format(it) }
        } catch (_: Exception) {
            null
        }
    }

    private fun showDuplicateComparison() {
        screenMode = "duplicate"
        if (duplicatePairs.isEmpty()) { showSettings(); return }
        duplicateIndex = duplicateIndex.coerceIn(0, duplicatePairs.lastIndex)
        val (existing, candidate) = duplicatePairs[duplicateIndex]
        val layout = root()
        layout.addView(brandHeader("Paramètres", "Médiathèque", { showSettings() }))
        layout.addView(visualTitleArt(R.drawable.ui_duplicate_glyph, "DOUBLON DÉTECTÉ",
            "${duplicateIndex + 1} sur ${duplicatePairs.size} · fichiers strictement identiques (SHA-256)"))

        fun card(title: String, media: Media): LinearLayout = section(title).apply {
            addView(mediaArtwork(media, 190), LinearLayout.LayoutParams(-1, dp(190)))
            addView(heading(media.title, 16f))
            addView(note("Album : ${media.album} · Catégorie : ${getCategory(media).ifEmpty { "Non classées" }} · ${formatBytes(media.size)}"))
        }
        val pair = LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL }
        pair.addView(card("IMAGE EXISTANTE", existing), LinearLayout.LayoutParams(0,-2,1f).apply { marginEnd=dp(4) })
        pair.addView(card("DOUBLON À EXAMINER", candidate), LinearLayout.LayoutParams(0,-2,1f).apply { marginStart=dp(4) })
        layout.addView(pair, LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=dp(10) })
        val marked = duplicateTrashSelection.contains(candidate.key)
        layout.addView(primaryAction(if (marked) "✓ Retirer de la sélection corbeille" else "▣ Sélectionner ce doublon pour la corbeille") {
            if (!duplicateTrashSelection.add(candidate.key)) duplicateTrashSelection.remove(candidate.key)
            showDuplicateComparison()
        }, LinearLayout.LayoutParams(-1,dp(58)).apply { bottomMargin=dp(8) })
        layout.addView(action("Conserver les deux") { duplicateTrashSelection.remove(candidate.key); nextDuplicate() },
            LinearLayout.LayoutParams(-1,dp(54)).apply { bottomMargin=dp(8) })
        val nav = LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL }
        nav.addView(action("‹ Précédent") { if (duplicateIndex > 0) { duplicateIndex--; showDuplicateComparison() } }, LinearLayout.LayoutParams(0,dp(52),1f))
        nav.addView(action("Suivant ›") { nextDuplicate() }, LinearLayout.LayoutParams(0,dp(52),1f))
        layout.addView(nav)
        layout.addView(primaryAction("Terminer la comparaison") { finishDuplicateSession() }, LinearLayout.LayoutParams(-1,dp(56)).apply { topMargin=dp(8) })
        layout.addView(note("Aucune suppression automatique. Tu gardes toujours le contrôle."))
        layout.addView(visualFooter(), LinearLayout.LayoutParams(-1,dp(145)))
        setContentView(ScrollView(this).apply { setBackgroundColor(black); addView(layout) })
    }

    private fun nextDuplicate() {
        if (duplicateIndex < duplicatePairs.lastIndex) {
            duplicateIndex++
            showDuplicateComparison()
        } else finishDuplicateSession()
    }

    private fun finishDuplicateSession() {
        if (duplicateTrashSelection.isEmpty()) {
            Toast.makeText(this, "Comparaison terminée. Aucun fichier sélectionné pour la corbeille.", Toast.LENGTH_LONG).show()
            showSettings()
            return
        }
        val all = duplicatePairs.flatMap { listOf(it.first, it.second) }.distinctBy { it.key }
        val medias = all.filter { duplicateTrashSelection.contains(it.key) }
        AlertDialog.Builder(this).setTitle("Récapitulatif")
            .setMessage("${medias.size} doublon(s) sélectionné(s). Android demandera confirmation avant la mise à la corbeille.")
            .setPositiveButton("Continuer") { _, _ -> confirmTrashFinal(medias) }
            .setNegativeButton("Revenir", null).show()
    }

    private fun confirmReset() {
        AlertDialog.Builder(this).setTitle("Réinitialiser l’application ?")
            .setMessage("Les préférences et classements Lapibreizh seront effacés. Tes photos et vidéos resteront intactes.")
            .setPositiveButton("Continuer") { _, _ ->
                AlertDialog.Builder(this)
                    .setTitle("Dernière confirmation")
                    .setMessage("Cette action effacera les réglages, favoris, catégories personnalisées et classements enregistrés dans l’application. Les photos et vidéos originales resteront intactes. Confirmer ?")
                    .setPositiveButton("Oui, réinitialiser") { _, _ ->
                        prefs.edit().clear().apply()
                        selected.clear()
                        bitmapCache.evictAll()
                        showHome()
                    }
                    .setNegativeButton("Annuler", null)
                    .show()
            }.setNegativeButton("Annuler", null).show()
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes o"
        val kb = bytes / 1024.0
        if (kb < 1024) return String.format("%.0f Ko", kb)
        val mb = kb / 1024.0
        if (mb < 1024) return String.format("%.1f Mo", mb)
        return String.format("%.1f Go", mb / 1024.0)
    }

    @Deprecated("Used to support navigation on Android 8-12")
    override fun onBackPressed() {
        when(screenMode) {
            "home" -> super.onBackPressed()
            "dashboard" -> showHome()
            "images-catalog", "videos-catalog" -> showMediaDashboard(Route.IMAGES)
            "gallery" -> if (route == Route.VIDEOS) showVideosCategories() else showImagesCategories()
            "categories" -> exitCategoryManager()
            "editor" -> showCategoryManager(videoCategoryTab, categoryEntry)
            "crop-category" -> { pendingCategoryImage?.let { showCategoryEditor(it) } ?: showCategoryManager(videoCategoryTab, categoryEntry) }
            "import", "duplicate" -> showSettings()
            "delete", "progress", "result" -> loadGallery()
            else -> showHome()
        }
    }

    override fun onDestroy() {
        generation++
        io.shutdownNow()
        super.onDestroy()
    }
}
