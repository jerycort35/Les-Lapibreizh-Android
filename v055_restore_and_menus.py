from pathlib import Path
import subprocess
P=Path("app/src/main/java/fr/leslapibreizh/mediatheque/MainActivity.kt")
BASE="fb5a62b650376825ee43498d99e79bd95c7420f1"
source=subprocess.check_output(["git","show",f"{BASE}:{P}"],text=True)

source=source.replace(
'    private var pendingTrash = emptyList<Media>()\n',
'    private var pendingTrash = emptyList<Media>()\n'
'    private var duplicateComparisons = emptyList<Pair<Media,Media>>()\n'
'    private var duplicateComparisonIndex = 0\n'
'    private val duplicateTrashSelection = linkedSetOf<String>()\n'
)

a=source.index('    /** Only exact byte-for-byte IMAGE matches, identified by size then SHA-256. */')
b=source.index('    private fun sha256(uri: Uri): String?',a)
scan='''    /** Doublons exacts : images ET vidéos, SHA-256, jamais de suppression automatique. */
    private fun scanExactDuplicates() {
        if (galleryItems.isEmpty()) { toast("Ouvre d'abord une galerie contenant des médias."); return }
        val request=generation
        val candidatesMedia=galleryItems.filter { if(it.mime.startsWith("video/")) it.size in 1..500_000_000L else it.size in 1..80_000_000L }
        duplicateButton?.isEnabled=false
        toast("Recherche des doublons exacts en cours…")
        io.execute {
            val groups=mutableListOf<List<Media>>()
            candidatesMedia.groupBy { it.size }.values.filter { it.size>1 }.forEach { bucket ->
                val byHash=mutableMapOf<String,MutableList<Media>>()
                bucket.forEach { media ->
                    val hash=try { sha256(media.uri) } catch(_:Exception){ null }
                    if(hash!=null) byHash.getOrPut(hash){ mutableListOf() }.add(media)
                }
                groups += byHash.values.filter { it.size>1 }
            }
            val pairs=groups.flatMap { g -> g.drop(1).map { g.first() to it } }
            duplicateKeys=groups.flatten().map { it.key }.toSet()
            runOnUiThread {
                if(request!=generation || route==Route.HOME || route==Route.SETTINGS) return@runOnUiThread
                duplicateButton?.isEnabled=true
                duplicateComparisons=pairs
                duplicateComparisonIndex=0
                duplicateTrashSelection.clear()
                if(pairs.isEmpty()) {
                    duplicatesOnly=false; duplicateButton?.text="Doublons"
                    toast("Aucun doublon strictement identique détecté.")
                } else {
                    duplicatesOnly=true; duplicateButton?.text="Doublons ✓"
                    updateItems(); showDuplicateComparison()
                }
            }
        }
    }

'''
source=source[:a]+scan+source[b:]

a=source.index('    /** Comparaison avant toute action : les doublons affichés ici sont identiques au SHA-256. */')
b=source.index('    private fun showOperationResult(',a)
dup='''    /** Comparateur navigable : toutes les paires exactes, décision manuelle. */
    private fun showDuplicateComparison() {
        if(duplicateComparisons.isEmpty()) { toast("Aucun doublon exact à comparer."); loadGallery(); return }
        duplicateComparisonIndex=duplicateComparisonIndex.coerceIn(0,duplicateComparisons.lastIndex)
        val (first,second)=duplicateComparisons[duplicateComparisonIndex]
        val content=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(9),dp(7),dp(9),dp(10)); setBackgroundColor(black) }
        val top=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL }
        top.addView(action("‹ Retour"){loadGallery()},LinearLayout.LayoutParams(dp(95),dp(43)))
        top.addView(heading("DOUBLONS",20f),LinearLayout.LayoutParams(0,dp(43),1f))
        top.addView(note("${duplicateComparisonIndex+1} sur ${duplicateComparisons.size}"),LinearLayout.LayoutParams(dp(90),dp(43)))
        content.addView(top)
        content.addView(note("Fichiers strictement identiques selon SHA-256 · aucune suppression automatique."))
        val pair=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL }
        listOf("IMAGE EXISTANTE" to first,"NOUVELLE IMAGE" to second).forEach { (label,media) ->
            val box=LinearLayout(this).apply {
                orientation=LinearLayout.VERTICAL; gravity=Gravity.CENTER
                background=shape(Color.rgb(22,18,14),gold,11); setPadding(dp(5),dp(5),dp(5),dp(5))
                addView(heading(label,12f))
                val iv=ImageView(this@MainActivity).apply { scaleType=ImageView.ScaleType.FIT_CENTER; tag=media.key; setBackgroundColor(Color.BLACK) }
                addView(iv,LinearLayout.LayoutParams(-1,dp(205)))
                addView(note("${media.title}\\n${media.album}\\n${media.size/1024} Ko"))
                io.execute { val bm=try { thumbnail(media,720) } catch(_:Exception){null}; if(bm!=null) iv.post { if(iv.tag==media.key) iv.setImageBitmap(bm) } }
            }
            pair.addView(box,LinearLayout.LayoutParams(0,-2,1f).apply { marginStart=dp(3);marginEnd=dp(3) })
        }
        content.addView(pair)
        content.addView(action(if(duplicateTrashSelection.contains(second.key)) "✓ Retirer de la sélection corbeille" else "Sélectionner la nouvelle copie pour la corbeille") {
            if(!duplicateTrashSelection.add(second.key)) duplicateTrashSelection.remove(second.key)
            showDuplicateComparison()
        },LinearLayout.LayoutParams(-1,dp(50)).apply { topMargin=dp(7) })
        val nav=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL }
        nav.addView(action("‹ Précédent"){ if(duplicateComparisonIndex>0){duplicateComparisonIndex--;showDuplicateComparison()} },LinearLayout.LayoutParams(0,dp(48),1f))
        nav.addView(action("Passer / Suivant ›"){ if(duplicateComparisonIndex<duplicateComparisons.lastIndex){duplicateComparisonIndex++;showDuplicateComparison()} else finishDuplicateReview() },LinearLayout.LayoutParams(0,dp(48),1f))
        content.addView(nav)
        content.addView(note("${duplicateTrashSelection.size} fichier(s) marqué(s) · rien n'est supprimé avant confirmation Android."))
        setContentView(ScrollView(this).apply { addView(content) })
    }

    private fun finishDuplicateReview() {
        val files=galleryItems.filter { duplicateTrashSelection.contains(it.key) }
        if(files.isEmpty()) { toast("Comparaison terminée : aucun fichier sélectionné."); loadGallery(); return }
        showTrashConfirmation(files)
    }

'''
source=source[:a]+dup+source[b:]

a=source.index('    private fun showSettings() {')
b=source.index('    @Deprecated("Used to support navigation on Android 8-12")',a)
settings='''    private fun showSettings() {
        route=Route.SETTINGS; dashboardVisible=false; selected.clear()
        fun section(title:String,subtitle:String?=null)=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL; setPadding(dp(10),dp(8),dp(10),dp(8))
            background=shape(Color.rgb(24,20,15),Color.rgb(91,70,39),12)
            addView(TextView(this@MainActivity).apply { text=title; textSize=17f; setTextColor(gold); typeface=android.graphics.Typeface.create("serif",android.graphics.Typeface.BOLD) })
            if(subtitle!=null) addView(note(subtitle))
        }
        fun toggle(label:String,key:String,default:Boolean,enabled:Boolean=true)=Switch(this).apply {
            text=label; textSize=13f; setTextColor(if(enabled) cream else 0xFF888888.toInt())
            isChecked=prefs.getBoolean(key,default); isEnabled=enabled
            if(enabled) setOnCheckedChangeListener { _,v -> prefs.edit().putBoolean(key,v).apply() }
        }
        val content=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(8),dp(4),dp(8),dp(12)); setBackgroundColor(black) }
        content.addView(action("‹ Retour"){showHome()},LinearLayout.LayoutParams(dp(105),dp(43)))
        content.addView(hero(R.drawable.hero_home,115))
        content.addView(heading("⚙  PARAMÈTRES",24f)); content.addView(note("Tout personnaliser, à ta façon"))

        val general=section("Général")
        general.addView(note("Thème : Noir & Or Lapibreizh · Langue : Français"))
        general.addView(toggle("Son et vibrations","setting_vibrations",true))
        general.addView(toggle("Animations","setting_animations",true))
        content.addView(general,LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=dp(8)})

        val imports=section("Import des albums existants","Analyse locale. Les originaux restent à leur emplacement.")
        imports.addView(action("Importer depuis la galerie"){navigate(Route.IMAGES)},LinearLayout.LayoutParams(-1,dp(48)))
        imports.addView(action("Importer / analyser tous les albums"){showSmartImport()},LinearLayout.LayoutParams(-1,dp(48)))
        content.addView(imports,LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=dp(8)})

        val automatic=section("Tri automatique — préparation","La partie automatique sera activée à la fin, après validation des menus.")
        automatic.addView(toggle("Activer le tri automatique","auto_enabled",false,false))
        automatic.addView(toggle("Proposer une catégorie","auto_suggest",true,false))
        automatic.addView(toggle("Créer une catégorie si besoin","auto_create",false,false))
        content.addView(automatic,LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=dp(8)})

        val types=section("Types de fichiers à analyser")
        types.addView(toggle("Images","setting_type_images",true))
        types.addView(toggle("Vidéos","setting_type_videos",true))
        types.addView(toggle("Captures d'écran","setting_type_screens",true))
        types.addView(toggle("Téléchargements","setting_type_downloads",true))
        types.addView(toggle("Partages (WhatsApp, etc.)","setting_type_shares",true))
        types.addView(toggle("Autres","setting_type_other",true))
        content.addView(types,LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=dp(8)})

        val dups=section("Gestion des doublons","SHA-256 exact · images et vidéos · aucune suppression automatique.")
        dups.addView(note("Action par défaut : Me demander à chaque fois"))
        dups.addView(action("Rechercher les doublons dans la galerie"){navigate(Route.IMAGES)},LinearLayout.LayoutParams(-1,dp(48)))
        content.addView(dups,LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=dp(8)})

        val cats=section("Mes catégories","Renommer ou supprimer le classement sans supprimer les fichiers.")
        savedCategories().forEach { category -> cats.addView(action("✎  $category"){showCategoryEditor(category)},LinearLayout.LayoutParams(-1,dp(44))) }
        content.addView(cats,LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=dp(8)})

        val backup=section("Sauvegarde du classement")
        backup.addView(action("Exporter la sauvegarde JSON"){exportCategories()},LinearLayout.LayoutParams(-1,dp(48)))
        backup.addView(action("Importer une sauvegarde JSON"){importCategories()},LinearLayout.LayoutParams(-1,dp(48)))
        content.addView(backup,LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=dp(8)})

        val storage=section("Espace de stockage","Les copies sont créées uniquement dans le dossier choisi.")
        storage.addView(action("Ouvrir la médiathèque"){navigate(Route.IMAGES)},LinearLayout.LayoutParams(-1,dp(48)))
        content.addView(storage,LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=dp(8)})

        val other=section("Autres options")
        other.addView(action("Vider le cache des miniatures"){bitmapCache.evictAll();toast("Cache des miniatures vidé.")},LinearLayout.LayoutParams(-1,dp(46)))
        other.addView(note("Réinitialisation complète protégée : aucune suppression de réglages en un clic."))
        other.addView(note("Version 0.5.5 · base stable V0.5.3 restaurée"))
        content.addView(other)
        content.addView(ImageView(this).apply { setImageResource(R.drawable.home_footer); scaleType=ImageView.ScaleType.FIT_XY },LinearLayout.LayoutParams(-1,dp(96)).apply{topMargin=dp(8)})

        val screen=root().apply{setPadding(0,0,0,0)}
        screen.addView(ScrollView(this).apply{addView(content)},LinearLayout.LayoutParams(-1,0,1f))
        screen.addView(navBar(Route.SETTINGS)); setContentView(screen)
    }

'''
source=source[:a]+settings+source[b:]
P.write_text(source,encoding="utf-8")
print("MainActivity V0.5.5 généré :",len(source.splitlines()),"lignes")
