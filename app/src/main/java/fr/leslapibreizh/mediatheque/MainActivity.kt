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
 * V0.4.1 : correction de la composition visuelle à partir des maquettes validées, tri et favoris,
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
    private var sortMode = 0 // Date ↓, Date ↑, Nom A→Z, Nom Z→A, Taille ↓, Type, Durée ↓
    private var dashboardVisible = false
    private var sortButton: Button? = null
    private var visibleLimit = 120
    private var imageColumns = 4
    private var videoColumns = 3
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
        val size: Long,
        val duration: Long = 0L
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
        setBackgroundColor(black)
        val entries = listOf(
            Triple("⌂", "Accueil", Route.HOME),
            Triple("▧", "Images", Route.IMAGES),
            Triple("▶", "Vidéos", Route.VIDEOS),
            Triple("♥", "Favoris", Route.FAVORITES),
            Triple("⚙", "Réglages", Route.SETTINGS)
        )
        entries.forEach { (symbol, name, destination) ->
            val item = LinearLayout(this@MainActivity).apply {
                orientation=LinearLayout.VERTICAL
                gravity=Gravity.CENTER
                setBackground(shape(if (active == destination) Color.rgb(77,53,24) else black,
                    if (active == destination) gold else Color.rgb(75,56,30), 7))
                val glyph=TextView(this@MainActivity).apply {
                    text=symbol; textSize=20f; gravity=Gravity.CENTER
                    setTextColor(if (active == destination) cream else gold)
                }
                val caption=TextView(this@MainActivity).apply {
                    text=name; textSize=10f; gravity=Gravity.CENTER; setTextColor(cream)
                }
                addView(glyph,LinearLayout.LayoutParams(-1,dp(28)))
                addView(caption,LinearLayout.LayoutParams(-1,dp(18)))
                setOnClickListener {
                    when (destination) {
                        Route.HOME -> showHome()
                        Route.SETTINGS -> showSettings()
                        else -> navigate(destination)
                    }
                }
                isClickable=true; isFocusable=true
                contentDescription=name
            }
            addView(item,LinearLayout.LayoutParams(0,dp(53),1f).apply {
                marginStart=dp(1); marginEnd=dp(1)
            })
        }
    }

    /** An approved, text-bearing image is an actual button, never a decorative fake control. */
    private fun artworkButton(art: Int, description: String, onClick: () -> Unit): ImageView =
        ImageView(this).apply {
            setImageResource(art)
            scaleType=ImageView.ScaleType.FIT_XY
            contentDescription=description
            isClickable=true
            isFocusable=true
            setOnClickListener { onClick() }
        }

    private fun homeShortcut(iconRes: Int, label: String, onClick: () -> Unit): LinearLayout =
        LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL
            gravity=Gravity.CENTER
            setPadding(dp(3),dp(4),dp(3),dp(4))
            setBackground(shape(Color.rgb(21,18,14),gold,11))
            val image=ImageView(this@MainActivity).apply {
                setImageResource(iconRes)
                setColorFilter(gold)
            }
            addView(image,LinearLayout.LayoutParams(dp(31),dp(32)))
            addView(TextView(this@MainActivity).apply {
                text=label
                textSize=11f
                gravity=Gravity.CENTER
                setTextColor(cream)
                typeface=android.graphics.Typeface.create("serif",android.graphics.Typeface.BOLD)
                maxLines=2
            },LinearLayout.LayoutParams(-1,dp(37)))
            setOnClickListener { onClick() }
            isClickable=true; isFocusable=true; contentDescription=label
        }

    private fun showHome() {
        route=Route.HOME
        dashboardVisible=false
        selected.clear()
        val screen=root().apply { setPadding(0,0,0,0) }
        val scroller=ScrollView(this).apply {
            isFillViewport=true
            overScrollMode=View.OVER_SCROLL_NEVER
        }
        val content=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL
            setBackgroundColor(black)
        }
        // The full original cover is displayed at its own aspect ratio: no cropped rabbit faces or stretched lettering.
        val cover=ImageView(this).apply {
            setImageResource(R.drawable.hero_home)
            scaleType=ImageView.ScaleType.FIT_XY
            contentDescription="Les deux Lapibreizh, phare breton et titre de la médiathèque"
        }
        val usableWidth=resources.displayMetrics.widthPixels
        val coverHeight=(usableWidth*690f/864f+0.5f).toInt()
        val cardHeight=(usableWidth-dp(20))/2
        content.addView(cover,LinearLayout.LayoutParams(-1,coverHeight))
        val pair=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL }
        pair.addView(artworkButton(R.drawable.home_images_card,"Images : organiser et partager") {
            navigate(Route.IMAGES)
        },LinearLayout.LayoutParams(0,cardHeight,1f).apply { marginStart=dp(7); marginEnd=dp(3) })
        pair.addView(artworkButton(R.drawable.home_videos_card,"Montages vidéo : organiser et partager") {
            navigate(Route.VIDEOS)
        },LinearLayout.LayoutParams(0,cardHeight,1f).apply { marginStart=dp(3); marginEnd=dp(7) })
        content.addView(pair,LinearLayout.LayoutParams(-1,cardHeight).apply { topMargin=dp(7) })
        val shortcuts=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL }
        val actions=listOf(
            Triple(android.R.drawable.ic_menu_search,"Retrouver\nune image/vidéo",0),
            Triple(android.R.drawable.ic_menu_agenda,"À classer",1),
            Triple(android.R.drawable.ic_menu_gallery,"Renvoyer\ndans la galerie",2),
            Triple(android.R.drawable.ic_menu_manage,"Paramètres",3)
        )
        actions.forEach { (image,label,index) ->
            val callback: () -> Unit = when(index) {
                0 -> ({
                    if(hasPermission(Route.IMAGES)) showGallery(Route.IMAGES)
                    else navigate(Route.IMAGES)
                })
                1 -> ({ navigate(Route.UNSORTED) })
                2 -> ({
                    if(hasPermission(Route.IMAGES)) {
                        showGallery(Route.IMAGES)
                        toast("Sélectionne les fichiers à renvoyer, puis utilise « Copier » et choisis le dossier Galerie.")
                    } else navigate(Route.IMAGES)
                })
                else -> ({ showSettings() })
            }
            shortcuts.addView(homeShortcut(image,label,callback),
                LinearLayout.LayoutParams(0,dp(81),1f).apply {
                    marginStart=dp(if (index==0) 7 else 3)
                    marginEnd=dp(if (index==3) 7 else 3)
                })
        }
        content.addView(shortcuts,LinearLayout.LayoutParams(-1,dp(84)).apply { topMargin=dp(6) })
        val footer=ImageView(this).apply {
            setImageResource(R.drawable.home_footer)
            scaleType=ImageView.ScaleType.FIT_XY
            contentDescription="Créer, classer, partager, revivre — Les Lapibreizh toujours avec vous"
        }
        content.addView(footer,LinearLayout.LayoutParams(-1,dp(96),1f).apply {
            topMargin=dp(6)
        })
        scroller.addView(content,FrameLayout.LayoutParams(-1,-2))
        screen.addView(scroller,LinearLayout.LayoutParams(-1,-1))
        setContentView(screen)
        // Cover and cards have natural dimensions; only the illustrated footer grows on tall phones.
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
            "épisode" in key || "episode" in key -> R.drawable.tile_episodes_video
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

    private fun displayCategoryName(name: String, video: Boolean): String = when {
        name == "Sans catégorie" -> if (video) "Autres" else "À classer"
        name == "Académie" -> "Académie des Lapibreizh"
        name == "Sport" -> if (video) "Section sportive" else "Fiches sportives"
        name == "Restaurant" -> "Restaurant des Lapibreizh"
        name == "Voyages" -> "Voyages de Carnot"
        name == "Épisodes" -> "Épisodes des Lapibreizh"
        else -> name
    }

    private fun videoCategories(): List<String> {
        val planned=listOf("Épisodes", "Fiches vertes", "Fiches bleues", "Fiches orange",
            "Fiches rouges", "Fiches violettes", "Fiches bonus", "Cueillette",
            "Restaurant", "Voyages", "Bricolage", "Sport")
        return (planned + savedCategories().filter { it !in planned }).distinct()
    }

    /** Actual categories and MediaStore counts. References never supply fictitious counts. */
    private fun showCategoryDashboard(target: Route) {
        route=target
        dashboardVisible=true
        if(target!=Route.VIDEOS && sortMode==6) sortMode=0
        categoryFilter=null
        albumFilter=null
        selected.clear()
        val request=++generation
        val video=target==Route.VIDEOS
        val screen=root().apply { setPadding(dp(4),dp(2),dp(4),dp(2)) }
        val scrolling=ScrollView(this).apply { isFillViewport=false }
        val content=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
        content.addView(action("‹ Retour") { showHome() },LinearLayout.LayoutParams(dp(95),dp(42)))
        content.addView(hero(if(video) R.drawable.hero_videos else R.drawable.hero_images,122))
        val search=EditText(this).apply {
            hint=if(video) "Rechercher une vidéo…" else "Rechercher une image…"
            setSingleLine(true); textSize=15f; setTextColor(cream)
            setHintTextColor(0xFFAAAAAA.toInt())
            setBackground(shape(Color.rgb(20,20,20),gold,12))
            setPadding(dp(13),0,dp(10),0)
        }
        val searchRow=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL }
        searchRow.addView(search,LinearLayout.LayoutParams(0,dp(45),1f))
        searchRow.addView(action("☷ Filtres") { showGallery(target) },LinearLayout.LayoutParams(dp(95),dp(45)).apply { marginStart=dp(5) })
        content.addView(searchRow,LinearLayout.LayoutParams(-1,dp(47)).apply { topMargin=dp(4) })
        val status=note("Chargement de la médiathèque…")
        content.addView(status)
        val cards=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
        content.addView(cards)
        content.addView(action("▧ Ouvrir la galerie complète") { showGallery(target) },
            LinearLayout.LayoutParams(-1,dp(44)).apply { topMargin=dp(7) })
        content.addView(action("+ Créer et gérer mes catégories") { showSettings() },
            LinearLayout.LayoutParams(-1,dp(44)).apply { topMargin=dp(4) })
        scrolling.addView(content)
        screen.addView(scrolling,LinearLayout.LayoutParams(-1,0,1f))
        screen.addView(navBar(target))
        setContentView(screen)
        io.execute {
            val items=queryMedia(target)
            runOnUiThread {
                if(request!=generation || route!=target || !dashboardVisible) return@runOnUiThread
                galleryItems=items
                status.text="${items.size} ${if(video) "vidéo(s)" else "image(s)"} accessibles · choix d’une catégorie"
                val counts=items.groupingBy { getCategory(it) }.eachCount()
                val curatedVideos=setOf("Épisodes","Fiches vertes","Fiches bleues","Fiches orange",
                    "Fiches rouges","Fiches violettes","Fiches bonus","Cueillette",
                    "Restaurant","Voyages","Bricolage","Sport")
                val videoNames=videoCategories().toSet()
                val names=if(video) videoCategories().filter { it in curatedVideos || (counts[it] ?: 0)>0 } +
                    "Sans catégorie" else savedCategories()+"Sans catégorie"
                val cols=if(resources.configuration.screenWidthDp < 350) 2 else 3
                val width=(resources.displayMetrics.widthPixels - dp(22) - dp(cols*7))/cols
                val cardHeight=width+dp(12)
                val allCards=mutableListOf<Pair<String,LinearLayout>>()
                names.distinct().forEach { name ->
                    val count=if(video && name=="Sans catégorie")
                        items.count { getCategory(it) !in videoNames }
                    else counts[if(name=="Sans catégorie") "" else name] ?: 0
                    val title=displayCategoryName(name,video)
                    val card=FrameLayout(this@MainActivity).apply {
                        setBackground(shape(Color.rgb(22,18,14),gold,11))
                        clipToOutline=true
                        val picture=ImageView(this@MainActivity).apply {
                            setImageResource(if(name=="Sans catégorie") R.drawable.tile_aclasser
                                else categoryArtwork(name,video))
                            scaleType=ImageView.ScaleType.CENTER_CROP
                            contentDescription="Illustration $title"
                        }
                        addView(picture,FrameLayout.LayoutParams(-1,-1))
                        val text=TextView(this@MainActivity).apply {
                            this.text="$title\n$count ${if(video) "vidéo(s)" else "image(s)"}"
                            textSize=12f
                            maxLines=3
                            gravity=Gravity.BOTTOM or Gravity.START
                            setTextColor(Color.WHITE)
                            setShadowLayer(4f,1f,1f,Color.BLACK)
                            setPadding(dp(6),dp(3),dp(3),dp(7))
                            background=GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                                intArrayOf(Color.TRANSPARENT,0xE3000000.toInt())).apply { cornerRadius=dp(10).toFloat() }
                        }
                        addView(text,FrameLayout.LayoutParams(-1,dp(69),Gravity.BOTTOM))
                        setOnClickListener {
                            categoryFilter=if(name=="Sans catégorie") (if(video) "__VIDEO_OTHER__" else "") else name
                            selected.clear();searchQuery="";screensOnly=false;duplicatesOnly=false
                            visibleLimit=120
                            loadGallery()
                        }
                        isClickable=true; isFocusable=true
                        contentDescription="$title, $count éléments"
                    }
                    val rowHolder=LinearLayout(this@MainActivity).apply { orientation=LinearLayout.VERTICAL }
                    rowHolder.addView(card,LinearLayout.LayoutParams(-1,cardHeight))
                    allCards.add(name to rowHolder)
                }
                fun renderCategoryCards(term: String) {
                    cards.removeAllViews()
                    val found=allCards.filter { (name,_) ->
                        term.isEmpty() || displayCategoryName(name,video).lowercase().contains(term)
                    }
                    if(found.isEmpty()) {
                        cards.addView(note("Aucune catégorie ne correspond à cette recherche."))
                    }
                    found.chunked(cols).forEach { group ->
                        val row=LinearLayout(this@MainActivity).apply { orientation=LinearLayout.HORIZONTAL }
                        group.forEach { (_,tile) ->
                            (tile.parent as? ViewGroup)?.removeView(tile)
                            row.addView(tile,LinearLayout.LayoutParams(0,cardHeight,1f).apply {
                                marginStart=dp(3);marginEnd=dp(3)
                            })
                        }
                        repeat(cols-group.size) {
                            row.addView(View(this@MainActivity),LinearLayout.LayoutParams(0,1,1f))
                        }
                        cards.addView(row,LinearLayout.LayoutParams(-1,cardHeight).apply {
                            bottomMargin=dp(6)
                        })
                    }
                }
                renderCategoryCards("")
                search.addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?,start:Int,count:Int,after:Int) {}
                    override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int) {
                        renderCategoryCards(s?.toString()?.trim()?.lowercase() ?: "")
                    }
                    override fun afterTextChanged(s: Editable?) {}
                })
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
        if(next!=Route.VIDEOS && sortMode==6) sortMode=0
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
        val toolbar=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL }
        toolbar.addView(action("‹ Retour") {
            if(route==Route.IMAGES || route==Route.VIDEOS) showCategoryDashboard(route)
            else showHome()
        },LinearLayout.LayoutParams(dp(93),dp(44)))
        toolbar.addView(heading(title,17f),LinearLayout.LayoutParams(0,dp(44),1f))
        layout.addView(toolbar)
        layout.addView(hero(if(route==Route.VIDEOS) R.drawable.hero_videos else R.drawable.hero_images,99))
        if(categoryFilter!=null) layout.addView(heading(
            if(categoryFilter=="") "À classer" else if(categoryFilter=="__VIDEO_OTHER__") "Autres"
            else displayCategoryName(categoryFilter!!,route==Route.VIDEOS),16f))
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
        layout.addView(search, LinearLayout.LayoutParams(-1,dp(43)))
        val sizes=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL }
        sizes.addView(note("Vignettes :"),LinearLayout.LayoutParams(0,dp(37),1f))
        listOf("Petites" to 4,"Moyennes" to 3,"Grandes" to 2).forEach { (name,columns) ->
            val button=action(name) {
                if(route==Route.VIDEOS) videoColumns=columns else imageColumns=columns
                grid?.numColumns=columns
                galleryAdapter?.notifyDataSetChanged()
            }
            sizes.addView(button,LinearLayout.LayoutParams(0,dp(37),1f))
        }
        layout.addView(sizes,LinearLayout.LayoutParams(-1,dp(38)))
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
            numColumns = if(route==Route.VIDEOS) videoColumns else imageColumns
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
                val columns=if(route==Route.VIDEOS) videoColumns else imageColumns
                val imageHeight=maxOf(dp(76),(resources.displayMetrics.widthPixels-dp(27))/columns)
                frame.layoutParams=AbsListView.LayoutParams(-1,imageHeight)
                frame.addView(image,FrameLayout.LayoutParams(-1,-1))
                val caption = TextView(this@MainActivity).apply {
                    val duration=if(media.mime.startsWith("video/") && media.duration>0) {
                        val total=media.duration/1000
                        "▶ %02d:%02d ".format(total/60,total%60)
                    } else if(media.mime.startsWith("video/")) "▶ " else ""
                    text=duration+media.title
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
                MediaStore.MediaColumns.DATE_ADDED, "bucket_display_name") +
                (if(images) emptyArray<String>() else arrayOf(MediaStore.Video.VideoColumns.DURATION))
            try {
                contentResolver.query(collection, columns, null, null,
                    "${MediaStore.MediaColumns.DATE_ADDED} DESC")?.use { c ->
                    val idIx = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                    val titleIx = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                    val mimeIx = c.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
                    val sizeIx = c.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                    val dateIx = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
                    val albumIx = c.getColumnIndexOrThrow("bucket_display_name")
                    val durationIx=if(images) -1 else c.getColumnIndex(MediaStore.Video.VideoColumns.DURATION)
                    while (c.moveToNext()) {
                        val id = c.getLong(idIx)
                        val mime = c.getString(mimeIx) ?: (if (images) "image/*" else "video/*")
                        result.add(Media(id, android.content.ContentUris.withAppendedId(collection, id), mime,
                            c.getString(titleIx) ?: "Sans titre", c.getString(albumIx) ?: "Autres",
                            c.getLong(dateIx), c.getLong(sizeIx), if(durationIx>=0) c.getLong(durationIx) else 0L))
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
                (categoryFilter == null || (categoryFilter == "__VIDEO_OTHER__" &&
                    getCategory(media) !in videoCategories()) || getCategory(media) == categoryFilter) &&
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
            6 -> filtered.sortedByDescending { it.duration }
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

    private fun sortShortLabel() = arrayOf("Date ↓ ▾", "Date ↑ ▾", "Nom A-Z ▾", "Nom Z-A ▾", "Taille ↓ ▾", "Type ▾", "Durée ↓ ▾")[sortMode]

    private fun chooseSort() {
        val labels= if(route==Route.VIDEOS) arrayOf("Date : plus récent d’abord", "Date : plus ancien d’abord",
            "Nom : A → Z", "Nom : Z → A", "Taille : plus grand d’abord", "Type de fichier puis nom",
            "Durée : la plus longue d’abord") else arrayOf("Date : plus récent d’abord", "Date : plus ancien d’abord",
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
        val cats = if(route==Route.VIDEOS) videoCategories() else savedCategories()
        val choices = (listOf("Sans catégorie") + cats).toTypedArray()
        AlertDialog.Builder(this).setTitle("Classer ${selected.size} élément(s)")
            .setItems(choices) { _, index ->
                val category = if (index == 0) "" else cats[index - 1]
                val edit = prefs.edit()
                if(category.isNotEmpty() && category !in savedCategories()) {
                    edit.putString("category_names",(savedCategories()+category).joinToString("\u001f"))
                }
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
        layout.addView(note("Version 0.4.1 · Appui long sur une miniature pour sélectionner."))
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
