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
import android.os.Environment
import android.os.StatFs
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
import java.security.MessageDigest

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
        "Personnages", "Lapins réels", "À classer")

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
        // Sécurité V0.5.6 : aucun classement automatique tant qu’il n’est pas validé.
        prefs.edit().putBoolean("auto_sort", false).putBoolean("auto_suggest", false).putBoolean("auto_create", false).apply()
        showHome()
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

    private fun navBar(active: Route): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(0, dp(5), 0, dp(3))
        listOf(
            Triple("⌂\nAccueil", Route.HOME) { showHome() },
            Triple(if (active == Route.UNSORTED) "▣\nPhotos" else "▣\nImages", Route.IMAGES) {
                if (active == Route.UNSORTED) setUnsortedFilter(UnsortedFilter.PHOTOS)
                else navigate(Route.IMAGES)
            },
            Triple("▶\nVidéos", Route.VIDEOS) {
                if (active == Route.UNSORTED) setUnsortedFilter(UnsortedFilter.VIDEOS)
                else navigate(Route.VIDEOS)
            },
            Triple("♡\nFavoris", Route.FAVORITES) { navigate(Route.FAVORITES) },
            Triple("⚙\nParamètres", Route.SETTINGS) { showSettings() }
        ).forEach { (label, r, click) ->
            val b=action(label, click).apply {
                textSize=11f
                if (r == active) background = GradientDrawable().apply {
                    setColor(Color.rgb(91,70,39)); cornerRadius=dp(11).toFloat()
                    setStroke(dp(2), gold)
                }
            }
            addView(b, LinearLayout.LayoutParams(0, dp(64), 1f).apply { marginStart=dp(2); marginEnd=dp(2) })
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
                              hits: List<Hotspot>, counts: Boolean = false) {
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
                        label.text = "${if (category == "À classer") grouped[""] ?: 0 else grouped[category] ?: 0} image(s)"
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
            addView(frame, FrameLayout.LayoutParams(width, height))
        })
    }

    private fun hs(x: Int, y: Int, w: Int, h: Int, label: String,
                   click: () -> Unit) = Hotspot(x, y, w, h, label, click)

    private fun openSearch(kind: Route) {
        val field = EditText(this).apply {
            hint = "Rechercher par nom de fichier"
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

    private fun openGalleryCategory(category: String, kind: Route = Route.IMAGES) {
        pendingCategory = if (category == "À classer") "" else category
        navigate(if (category == "À classer") Route.UNSORTED else kind)
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
            hs(237,1164,207,186,"À classer") { navigate(Route.UNSORTED) },
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
            hs(249,1097,210,185,"À classer") { navigate(Route.UNSORTED) },
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

    private fun showImagesCategories() {
        generation++
        screenMode = "images-catalog"
        route = Route.IMAGES
        selected.clear()
        val hits = mutableListOf(
            hs(15,8,140,68,"Retour") { showMediaDashboard(Route.IMAGES) },
            hs(17,278,643,69,"Rechercher") { openSearch(Route.IMAGES) },
            hs(670,278,180,69,"Filtres") { openFilters() })
        val xs = listOf(17,299,578)
        val ys = listOf(357,585,815)
        artworkCategories.take(9).forEachIndexed { index, category ->
            hits += hs(xs[index % 3], ys[index / 3], 270, 223, category) { openGalleryCategory(category) }
        }
        hits += hs(17,1055,269,204,"À classer") { navigate(Route.UNSORTED) }
        hits += hs(17,1265,197,131,"Trier") { navigate(Route.IMAGES) }
        hits += hs(226,1265,196,131,"Sélectionner") { navigate(Route.IMAGES) }
        hits += hs(435,1265,195,131,"Classer") { navigate(Route.IMAGES) }
        hits += hs(640,1265,208,131,"Galerie") { navigate(Route.IMAGES) }
        hits += hs(16,1405,167,120,"Accueil") { showHome() }
        hits += hs(183,1405,170,120,"Images") { navigate(Route.IMAGES) }
        hits += hs(353,1405,170,120,"Vidéos") { showVideosCategories() }
        hits += hs(523,1405,170,120,"Favoris") { navigate(Route.FAVORITES) }
        hits += hs(693,1405,160,120,"Paramètres") { showSettings() }
        artworkScreen(R.drawable.ui_images_exact,864,1536,hits,counts=true)
    }

    private fun showVideosCategories() {
        generation++
        screenMode = "videos-catalog"
        route = Route.VIDEOS
        selected.clear()
        val categories = listOf("Épisodes des Lapibreizh", "Fiches vertes", "Fiches bleues",
            "Fiches orange", "Fiches rouges", "Fiches violettes", "Fiches bonus",
            "Cueillette", "Restaurant des Lapibreizh", "Voyages de Carnot", "Bricolage",
            "Fiches sportives", "Autres")
        val hits = mutableListOf(hs(13,7,139,63,"Retour") { showMediaDashboard(Route.IMAGES) })
        val xs = listOf(16,297,578); val ys = listOf(274,489,701,933)
        categories.take(12).forEachIndexed { i, cat ->
            hits += hs(xs[i % 3],ys[i / 3],271,208,cat) { openGalleryCategory(cat,Route.VIDEOS) }
        }
        hits += hs(15,1165,831,177,"Autres vidéos") { openOtherVideos() }
        hits += hs(15,1356,167,173,"Accueil") { showHome() }
        hits += hs(183,1356,170,173,"Images") { showImagesCategories() }
        hits += hs(353,1356,170,173,"Vidéos") { navigate(Route.VIDEOS) }
        hits += hs(523,1356,170,173,"Favoris") { navigate(Route.FAVORITES) }
        hits += hs(693,1356,160,173,"Paramètres") { showSettings() }
        artworkScreen(R.drawable.ui_videos_exact,864,1536,hits)
    }

    private fun openOtherVideos() {
        pendingCategory = ""
        navigate(Route.VIDEOS)
    }

    private fun openFilters() {
        AlertDialog.Builder(this).setTitle("Filtres")
            .setItems(arrayOf("Toutes mes images", "Tous mes montages vidéo", "Sans catégorie", "Favoris")) { _, i ->
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
        return if (raw == null) canonicalCategories + listOf("Fiches vertes", "Fiches bleues", "Fiches orange",
            "Fiches rouges", "Fiches violettes", "Fiches bonus", "Cueillette", "Autres")
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

    // Identité native des écrans secondaires. Les zones tactiles ne sont pas dessinées dans l'image.
    private fun brandHeader(backLabel: String, page: String, onBack: () -> Unit): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(3))
            background = GradientDrawable().apply {
                setColor(black)
                setStroke(dp(1), gold)
            }
            val back = TextView(this@MainActivity).apply {
                text = "‹  $backLabel"; textSize = 13f; setTextColor(gold)
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                isClickable = true; contentDescription = "Retour vers $backLabel"
                setOnClickListener { onBack() }
            }
            addView(back, LinearLayout.LayoutParams(0, dp(78), 0.27f))
            addView(ImageView(this@MainActivity).apply {
                setImageResource(R.drawable.ui_brand_center)
                scaleType = ImageView.ScaleType.FIT_CENTER
                contentDescription = "Les Lapibreizh"
            }, LinearLayout.LayoutParams(0, dp(78), 0.43f))
            addView(TextView(this@MainActivity).apply {
                text = page; textSize = 14f; setTextColor(gold)
                gravity = Gravity.END or Gravity.CENTER_VERTICAL
                typeface = android.graphics.Typeface.create("serif", android.graphics.Typeface.BOLD)
            }, LinearLayout.LayoutParams(0, dp(78), 0.30f))
        }

    private fun scenicImage(res: Int, height: Int): ImageView = ImageView(this).apply {
        setImageResource(res)
        scaleType = ImageView.ScaleType.CENTER_CROP
        contentDescription = "Illustration bretonne Les Lapibreizh"
        background = GradientDrawable().apply {
            cornerRadius = dp(12).toFloat()
            setColor(background)
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

    private fun currentCategoryPhoto(category: String): ImageView = ImageView(this).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        contentDescription = "Photographie de la catégorie $category"
        val savedUri = prefs.getString("category_image_$category", null)
        if (!savedUri.isNullOrBlank()) {
            try { setImageURI(Uri.parse(savedUri)) }
            catch (_: Exception) { setImageResource(categoryArtwork(category)) }
        } else setImageResource(categoryArtwork(category))
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

    private fun showSettings() {
        generation++
        screenMode = "settings"
        route = Route.SETTINGS
        selected.clear()
        val layout = root().apply { setPadding(dp(8), dp(6), dp(8), dp(9)) }
        // Le bandeau validé contient déjà Retour, logo et Paramètres ; une seule zone Retour cliquable.
        layout.addView(settingsHeader { showHome() })

        val general = section("⚙  Général", "Personnalise l’apparence et le fonctionnement de l’application")
        val scenic = FrameLayout(this)
        scenic.addView(scenicImage(R.drawable.ui_settings_scene, 208), FrameLayout.LayoutParams(-1, dp(208)))
        scenic.addView(View(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(0xFF100E0B.toInt(), 0xEF100E0B.toInt(), 0x00100E0B))
        }, FrameLayout.LayoutParams(-1, dp(208)))
        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(5), dp(6), dp(2), dp(2))
        }
        controls.addView(simpleLabel("Thème : noir & or", 14f, gold))
        controls.addView(simpleLabel("Langue : Français", 13f))
        controls.addView(switchRow("Son / vibrations · À venir", "vibrations", false))
        controls.addView(switchRow("Animations · À venir", "animations", false))
        scenic.addView(controls, FrameLayout.LayoutParams(dp(220), dp(206), Gravity.TOP or Gravity.START))
        general.addView(scenic, LinearLayout.LayoutParams(-1, dp(208)))
        general.addView(note("Les thèmes alternatifs, le son et les animations seront activés plus tard."))
        layout.addView(general, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(9) })

        val imports = section("▣  Import des albums existants", "Retrouve tes albums sans copier ni modifier les originaux")
        val importActions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        importActions.addView(rowCard("▧", "Depuis la galerie", "Voir les images") { navigate(Route.IMAGES) },
            LinearLayout.LayoutParams(0, dp(83), 1f).apply { rightMargin = dp(3) })
        importActions.addView(rowCard("⇩", "Albums", "Choisir et classer") { showAlbumImport() },
            LinearLayout.LayoutParams(0, dp(83), 1f).apply { leftMargin = dp(3) })
        imports.addView(importActions)
        layout.addView(imports, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(9) })

        val auto = section("✦  Tri automatique", "Désactivé : toute affectation nécessite ta validation")
        auto.addView(switchRow("Tri automatique · Indisponible", "auto_sort", false))
        auto.addView(switchRow("Suggestion automatique · Indisponible", "auto_suggest", false))
        auto.addView(switchRow("Création automatique · Indisponible", "auto_create", false))
        layout.addView(auto, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(9) })

        val dup = section("▣  Gestion des doublons", "Comparer les fichiers par SHA-256, choisir toi-même")
        dup.addView(rowCard("▢", "Analyser les doublons", "Aucun effacement automatique") { findDuplicates() })
        layout.addView(dup, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(9) })

        val stat = StatFs(Environment.getDataDirectory().path)
        val total = stat.totalBytes.coerceAtLeast(1L)
        val free = stat.availableBytes
        val used = total - free
        val storageAndOptions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val storage = section("▰  Stockage", "${formatBytes(used)} / ${formatBytes(total)}")
        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000; progress = ((used * 1000L) / total).toInt()
        }
        storage.addView(bar, LinearLayout.LayoutParams(-1, dp(22)))
        storage.addView(simpleLabel("${formatBytes(free)} libres", 12f))
        storageAndOptions.addView(storage, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = dp(3) })
        val other = section("•••  Autres options")
        other.addView(action("Vider le cache") {
            AlertDialog.Builder(this).setTitle("Vider le cache ?")
                .setMessage("Les originaux ne seront pas supprimés.")
                .setPositiveButton("Vider") { _, _ ->
                    bitmapCache.evictAll()
                    try { cacheDir.deleteRecursively() } catch (_: Exception) {}
                    Toast.makeText(this, "Cache vidé", Toast.LENGTH_SHORT).show()
                }.setNegativeButton("Annuler", null).show()
        })
        other.addView(action("Réinitialiser") { confirmReset() })
        other.addView(action("À propos") {
            val version = try { packageManager.getPackageInfo(packageName, 0).versionName } catch (_: Exception) { "0.6.3" }
            AlertDialog.Builder(this).setTitle("Les Lapibreizh — La Médiathèque")
                .setMessage("Version $version\n\nLes médias originaux ne sont pas déplacés par le classement logique.")
                .setPositiveButton("Fermer", null).show()
        })
        storageAndOptions.addView(other, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(3) })
        layout.addView(storageAndOptions, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(9) })

        val cats = section("▣  Mes catégories", "Modifier la photo, le nom, l’icône et l’ordre")
        savedCategories().forEach { category ->
            cats.addView(rowCard("▣", category, "Modifier la catégorie") { showCategoryEditor(category) },
                LinearLayout.LayoutParams(-1, dp(68)).apply { bottomMargin = dp(4) })
        }
        cats.addView(primaryAction("+  Ajouter une catégorie") { addCategoryDialog() },
            LinearLayout.LayoutParams(-1, dp(55)))
        layout.addView(cats)
        layout.addView(visualFooter(), LinearLayout.LayoutParams(-1, dp(143)))
        val page=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL
            setBackgroundColor(black)
        }
        page.addView(ScrollView(this).apply {
            setBackgroundColor(black);addView(layout)
        },LinearLayout.LayoutParams(-1,0,1f))
        page.addView(navBar(Route.SETTINGS),LinearLayout.LayoutParams(-1,dp(76)))
        setContentView(page)
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
                    prefs.edit().putString("category_names", (savedCategories() + name).joinToString("\u001f")).apply()
                    showSettings()
                } else Toast.makeText(this, "Nom vide, trop long ou déjà utilisé", Toast.LENGTH_SHORT).show()
            }.setNegativeButton("Annuler", null).show()
    }

    private fun categoryArtwork(category: String): Int = when {
        category.contains("sport",true) -> R.drawable.art_bombarde
        category.contains("Restaurant",true) -> R.drawable.art_carnot
        category.contains("Voyage",true) -> R.drawable.art_cookie
        category.contains("Académie",true) -> R.drawable.art_quiberon
        category.contains("couleur",true) -> R.drawable.art_neige
        category.contains("Personnage",true) -> R.drawable.art_chocapic
        category.contains("Lapins réel",true) -> R.drawable.art_rabbits
        else -> R.drawable.art_edit_category
    }

    private fun showCategoryEditor(category: String) {
        screenMode = "editor"
        val layout = root().apply { setPadding(dp(8), dp(6), dp(8), dp(8)) }
        layout.addView(brandHeader("Catégories", "Modifier", { showSettings() }))
        layout.addView(visualTitle("⚙", "MODIFIER UNE CATÉGORIE", "Personnalise ta catégorie selon tes envies"))

        val identity = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity=Gravity.TOP }
        val photoPart = FrameLayout(this)
        photoPart.addView(currentCategoryPhoto(category), FrameLayout.LayoutParams(-1, dp(168)))
        photoPart.addView(action("▣  Changer l’image") {
            pendingCategoryImage = category
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                type = "image/*"; addCategory(Intent.CATEGORY_OPENABLE)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
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
        val iconLabel = simpleLabel("Icône", 12f, gold)
        fields.addView(iconLabel)
        val icons = listOf("⚒", "♧", "✿", "▣", "✦", "★", "◉")
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
        val preview = section("PRÉVISUALISATION")
        val previewRow = LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER_VERTICAL }
        previewRow.addView(ImageView(this).apply {
            setImageResource(R.drawable.ui_category_scene)
            scaleType=ImageView.ScaleType.CENTER_CROP
            contentDescription="Atelier Lapibreizh"
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
            if (newName!=category) e.remove("category_icon_$category")
            e.putString("category_icon_$newName",pickedIcon)
            e.apply()
            Toast.makeText(this,"Catégorie enregistrée",Toast.LENGTH_SHORT).show()
            showSettings()
        },LinearLayout.LayoutParams(-1,dp(58)).apply { bottomMargin=dp(9) })

        val org = section("ORGANISATION")
        org.addView(rowCard("⇅","Déplacer cette catégorie","Choisir sa position dans la liste") {
            val names=savedCategories()
            AlertDialog.Builder(this).setTitle("Nouvelle position de « $category »")
                .setItems(names.mapIndexed { index, n -> "${index+1}. $n" }.toTypedArray()) { _, idx ->
                    val ordered=names.toMutableList()
                    if(ordered.remove(category)) {
                        ordered.add(idx.coerceAtMost(ordered.size),category)
                        prefs.edit().putString("category_names",ordered.joinToString("\u001f")).apply()
                        Toast.makeText(this,"Position enregistrée",Toast.LENGTH_SHORT).show()
                    }
                }.setNegativeButton("Annuler",null).show()
        })
        layout.addView(org,LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=dp(9) })
        val statistics=section("STATISTIQUES")
        statistics.addView(rowCard("◴","Contenu de la catégorie","$count média(s) classé(s)") {
            AlertDialog.Builder(this).setTitle("Voir les médias de « $category »")
                .setItems(arrayOf("Images", "Vidéos")) { _, choice ->
                    pendingCategory=category
                    navigate(if(choice==0) Route.IMAGES else Route.VIDEOS)
                }.setNegativeButton("Annuler",null).show()
        })
        layout.addView(statistics,LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=dp(9) })
        val danger=section("SUPPRIMER LA CATÉGORIE",
            "Seul son classement sera retiré. Les photos et vidéos originales restent intactes.")
        danger.background=GradientDrawable().apply {
            setColor(Color.rgb(43,11,10)); cornerRadius=dp(14).toFloat()
            setStroke(dp(1),Color.rgb(236,71,64))
        }
        danger.addView(dangerAction("▣  Supprimer la catégorie") {
            AlertDialog.Builder(this).setTitle("Supprimer « $category » ?")
                .setMessage("Ses $count médias deviendront non classés. Aucun fichier original ne sera supprimé.")
                .setPositiveButton("Supprimer la catégorie") { _, _ ->
                    val e=prefs.edit().putString("category_names",
                        savedCategories().filterNot { it==category }.joinToString("\u001f"))
                    prefs.all.filterValues { it==category }.keys.filter { it.startsWith("media_") }
                        .forEach { e.remove(it) }
                    e.remove("category_image_$category");e.remove("category_icon_$category")
                    e.apply();showSettings()
                }.setNegativeButton("Annuler",null).show()
        },LinearLayout.LayoutParams(-1,dp(56)))
        layout.addView(danger)
        layout.addView(visualFooter(),LinearLayout.LayoutParams(-1,dp(145)))
        setContentView(ScrollView(this).apply { setBackgroundColor(black);addView(layout) })
    }

    private fun allKnownMediaKeysForCategory(category: String): Int =
        prefs.all.count { (k, v) -> k.startsWith("media_") && v == category }

    private fun showAlbumImport() {
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
        intro.addView(note("Sélectionne les albums, puis choisis une destination pour chacun. Aucun tri automatique."))
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
                val headingBox=section("SÉLECTION DES ALBUMS","Coche les albums à classer manuellement")
                albumList.addView(headingBox,LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=dp(8) })
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
                                val edit=prefs.edit()
                                checked.forEach { album ->
                                    groups[album]?.forEach { m -> edit.putString("media_${m.key}",destinations.getValue(album)) }
                                }
                                edit.apply()
                                Toast.makeText(this,"${chosen.size} médias classés",Toast.LENGTH_LONG).show()
                                showSettings()
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
        val cats = savedCategories()
        AlertDialog.Builder(this).setTitle("Déplacer / classer ${medias.size} média(s)")
            .setMessage("Choisis la catégorie de destination. Les fichiers originaux ne seront pas déplacés.")
            .setItems(cats.toTypedArray()) { _, index ->
                showMoveProgress(medias, cats[index])
            }.setNegativeButton("Annuler", null).show()
    }

    private fun showMoveProgress(medias: List<Media>, destination: String) {
        screenMode = "progress"
        val layout = root()
        layout.addView(brandHeader("Galerie", "Médiathèque", { loadGallery() }))
        layout.addView(visualTitleArt(R.drawable.ui_move_glyph, "CLASSEMENT EN COURS",
            "Classement logique : les fichiers originaux restent à leur emplacement."))
        val progressCard = section("PROGRESSION")
        val progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = medias.size }
        val txt = heading("0 / ${medias.size} fichiers", 20f)
        progressCard.addView(progress, LinearLayout.LayoutParams(-1, dp(28)))
        val percent = simpleLabel("0 %", 19f, gold)
        progressCard.addView(percent)
        progressCard.addView(txt)
        val origins = medias.map { getCategory(it).ifEmpty { "À classer" } }.distinct()
        progressCard.addView(note("Depuis : ${if (origins.size == 1) origins.first() else "Plusieurs catégories"}  →  Vers : $destination"))
        layout.addView(progressCard, LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=dp(10) })
        layout.addView(mediaPreviewStrip(medias), LinearLayout.LayoutParams(-1, dp(95)))
        layout.addView(section("ℹ  CLASSEMENT SÉCURISÉ",
            "Si un média pose problème, les autres continuent d’être traités. Les originaux restent à leur emplacement."),
            LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(10) })
        layout.addView(visualFooter(), LinearLayout.LayoutParams(-1, dp(145)))
        setContentView(ScrollView(this).apply { setBackgroundColor(black); addView(layout) })
        io.execute {
            val e = prefs.edit()
            medias.forEachIndexed { index, m ->
                e.putString("media_${m.key}", destination)
                runOnUiThread {
                    progress.progress = index + 1
                    percent.text = "${(index + 1) * 100 / medias.size} %"
                    txt.text = "${index + 1} / ${medias.size} fichiers"
                }
            }
            e.apply()
            runOnUiThread { showMoveResult(medias.size, destination) }
        }
    }

    private fun showMoveResult(count: Int, destination: String) {
        screenMode = "result"
        selected.clear()
        val layout = root()
        layout.addView(brandHeader("Galerie", "Médiathèque", { loadGallery() }))
        layout.addView(visualTitle("✓", "CLASSEMENT TERMINÉ !", "$count média(s) traités"))
        val result = section("✓  OPÉRATION RÉUSSIE",
            "$count média(s) sont maintenant classés dans « $destination ».\nLes fichiers originaux n’ont pas été déplacés.")
        result.background=GradientDrawable().apply {
            setColor(Color.rgb(12,40,20)); cornerRadius=dp(15).toFloat()
            setStroke(dp(1), Color.rgb(64,185,104))
        }
        layout.addView(result, LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=dp(12) })
        layout.addView(primaryAction("▣  Voir les fichiers classés") {
            AlertDialog.Builder(this).setTitle("Ouvrir « $destination »")
                .setItems(arrayOf("Images", "Vidéos")) { _, which ->
                    pendingCategory = destination
                    navigate(if (which == 0) Route.IMAGES else Route.VIDEOS)
                }.setNegativeButton("Annuler",null).show()
        }, LinearLayout.LayoutParams(-1, dp(58)).apply { bottomMargin=dp(8) })
        layout.addView(action("⌂  Retour à la médiathèque") { showMediaDashboard(Route.IMAGES) },
            LinearLayout.LayoutParams(-1, dp(58)))
        layout.addView(visualFooter(), LinearLayout.LayoutParams(-1, dp(155)))
        setContentView(ScrollView(this).apply { setBackgroundColor(black); addView(layout) })
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

    private fun confirmTrashFinal(medias: List<Media>) {
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
            .setPositiveButton("Oui, continuer") { _, _ -> requestTrash(medias) }
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
            if (resultCode == RESULT_OK) {
                selected.removeAll(pendingTrashKeys.toSet())
                duplicateTrashSelection.clear()
                Toast.makeText(this, "Médias placés dans la corbeille", Toast.LENGTH_LONG).show()
            }
            pendingTrashKeys = emptyList()
            if (route == Route.SETTINGS) showSettings() else loadGallery()
        } else if (requestCode == 305 && resultCode == RESULT_OK) {
            val category = pendingCategoryImage
            val uri = data?.data
            if (category != null && uri != null) {
                try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                catch (_: Exception) {}
                prefs.edit().putString("category_image_$category", uri.toString()).apply()
                Toast.makeText(this, "Photo de catégorie enregistrée", Toast.LENGTH_SHORT).show()
                pendingCategoryImage = null
                showCategoryEditor(category)
            }
        }
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
            addView(note("${media.album}  ·  ${formatBytes(media.size)}"))
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
            "import", "editor", "duplicate" -> showSettings()
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
