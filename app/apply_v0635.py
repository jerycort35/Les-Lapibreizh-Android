from pathlib import Path

p=Path("app/src/main/java/fr/leslapibreizh/mediatheque/MainActivity.kt")
s=p.read_text(encoding="utf-8")
MARKER="    /** V0.6.35 : sécurité galerie, compteurs et gestion des catégories. */"
if MARKER in s:
    print("V0.6.35 déjà appliquée")
    raise SystemExit(0)

def rep(old,new,label):
    global s
    if old not in s:
        raise SystemExit("V0.6.35 - bloc introuvable : "+label)
    s=s.replace(old,new,1)

rep(
'''        val count=page.label("${if(video)galleryItems.count{it.mime.startsWith("video/") && getCategory(it)==cat}else allKnownMediaKeysForCategory(cat)} $mediaWord",24f)
        place(count,175,384,425,31)
        text("Apprendre  •  Comprendre  •  Protéger  •  Partager",175,416,425,30,15f,gold)
        place(page.crop(616,348,229,70,"Changer de catégorie") {
            if(catalog) AlertDialog.Builder(this).setTitle("Changer de catégorie").setItems(savedCategories().toTypedArray()){_,i->openGalleryCategory(savedCategories()[i],kind)}.show()
            else catalogBack()
        },616,348,229,70)''',
'''        val count=page.label("${galleryItems.count{it.mime.startsWith(if(video)"video/" else "image/") && getCategory(it)==cat}} $mediaWord",24f)
        place(count,175,384,425,31)
        text("Apprendre  •  Comprendre  •  Protéger  •  Partager",175,416,425,30,15f,gold)
        if(catalog) place(View(this).apply {
            contentDescription="Modifier la catégorie $cat"
            isClickable=true
            setOnClickListener { showCategoryEditor(cat) }
        },26,343,574,103)
        place(page.crop(616,348,229,70,"Changer de catégorie") {
            if(catalog) AlertDialog.Builder(this).setTitle("Changer de catégorie").setItems(savedCategories().toTypedArray()){_,i->openGalleryCategory(savedCategories()[i],kind)}.show()
            else catalogBack()
        },616,348,229,70)
        if(catalog) place(page.button("Organiser",13f) { showCategoryOrganizer(video) },748,420,97,30)''',
"en-tête catégorie")

rep(
'''        place(page.button("Envoyer à la galerie\\n(0 / 1000)",20f){if(selectCatalogMedia()) {
            // Classification is logical: originals already remain in MediaStore.
            Toast.makeText(this,"Les originaux sont déjà présents dans la galerie du téléphone.",Toast.LENGTH_LONG).show()
        }},201,-237,214,78,true)''',
'''        place(page.button("Envoyer à la galerie\\n(0 / 1000)",20f){if(selectCatalogMedia()) {
            returnSelectedToGallery(video, catalog)
        }},201,-237,214,78,true)''',
"envoyer à la galerie")

rep(
'''            count.text="${if(video)galleryItems.count{it.mime.startsWith("video/") && getCategory(it)==cat}else allKnownMediaKeysForCategory(cat)} $mediaWord"
        }''',
'''            count.text="${galleryItems.count{it.mime.startsWith(if(video)"video/" else "image/") && getCategory(it)==cat}} $mediaWord"
        }''',
"rafraîchissement compteur")

old_card='''                return LinearLayout(this@MainActivity).apply {
                    orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER;background=page.border(cat in catalogSelected)
                    layoutParams=AbsListView.LayoutParams(-1,((if(catalogColumns==3)168 else 504/catalogColumns)*scale).toInt())
                    if(!plus && prefs.getString("category_image_$cat",null)?.let{File(it).exists()}==true) addView(currentCategoryPhoto(cat),LinearLayout.LayoutParams(-1,0,1f))
                    else if(video && !plus)addView(FrameLayout(this@MainActivity).apply{
                        addView(ImagesReferenceLayout(this@MainActivity,R.drawable.videos_catalog_master).crop(396,660,72,76,"Catégorie vidéo"),FrameLayout.LayoutParams((70*scale).toInt(),(74*scale).toInt(),Gravity.CENTER))
                    },LinearLayout.LayoutParams(-1,0,1f))
                    else addView(TextView(this@MainActivity).apply{text=if(plus)"+" else "?";setTextColor(if(plus)gold else cream);setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,70*scale);gravity=Gravity.CENTER;includeFontPadding=false},LinearLayout.LayoutParams(-1,0,1f))
                    addView(TextView(this@MainActivity).apply{text=if(plus)"Nouvelle\\ncatégorie" else  cat;typeface=android.graphics.Typeface.SERIF;setTextColor(cream);setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,25*scale);gravity=Gravity.CENTER;includeFontPadding=false},LinearLayout.LayoutParams(-1,-2))
                    if(!plus)addView(TextView(this@MainActivity).apply{text="${if(video)galleryItems.count{getCategory(it)==cat}else allKnownMediaKeysForCategory(cat)} $word";typeface=android.graphics.Typeface.SERIF;setTextColor(cream);setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,21*scale);gravity=Gravity.CENTER},LinearLayout.LayoutParams(-1,-2))
                    setOnClickListener {if(plus)addCategoryDialog()else if(catalogSelected.isNotEmpty()){if(!catalogSelected.add(cat))catalogSelected.remove(cat);notifyDataSetChanged();exactRefresh?.invoke()}else openGalleryCategory(cat,kind)}
                    setOnLongClickListener{if(!plus){if(!catalogSelected.add(cat))catalogSelected.remove(cat);notifyDataSetChanged();exactRefresh?.invoke()};true}
                }'''
new_card='''                val card=FrameLayout(this@MainActivity).apply {
                    background=page.border(cat in catalogSelected)
                    layoutParams=AbsListView.LayoutParams(-1,((if(catalogColumns==3)168 else 504/catalogColumns)*scale).toInt())
                }
                val body=LinearLayout(this@MainActivity).apply {
                    orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER
                    if(!plus && prefs.getString("category_image_$cat",null)?.let{File(it).exists()}==true) addView(currentCategoryPhoto(cat),LinearLayout.LayoutParams(-1,0,1f))
                    else if(video && !plus)addView(FrameLayout(this@MainActivity).apply{
                        addView(ImagesReferenceLayout(this@MainActivity,R.drawable.videos_catalog_master).crop(396,660,72,76,"Catégorie vidéo"),FrameLayout.LayoutParams((70*scale).toInt(),(74*scale).toInt(),Gravity.CENTER))
                    },LinearLayout.LayoutParams(-1,0,1f))
                    else addView(TextView(this@MainActivity).apply{text=if(plus)"+" else "?";setTextColor(if(plus)gold else cream);setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,70*scale);gravity=Gravity.CENTER;includeFontPadding=false},LinearLayout.LayoutParams(-1,0,1f))
                    addView(TextView(this@MainActivity).apply{text=if(plus)"Nouvelle\\ncatégorie" else cat;typeface=android.graphics.Typeface.SERIF;setTextColor(cream);setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,25*scale);gravity=Gravity.CENTER;includeFontPadding=false},LinearLayout.LayoutParams(-1,-2))
                    if(!plus)addView(TextView(this@MainActivity).apply{text="${galleryItems.count{it.mime.startsWith(if(video)"video/" else "image/") && getCategory(it)==cat}} $word";typeface=android.graphics.Typeface.SERIF;setTextColor(cream);setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,21*scale);gravity=Gravity.CENTER},LinearLayout.LayoutParams(-1,-2))
                    setOnClickListener {if(plus)addCategoryDialog()else if(catalogSelected.isNotEmpty()){if(!catalogSelected.add(cat))catalogSelected.remove(cat);notifyDataSetChanged();exactRefresh?.invoke()}else openGalleryCategory(cat,kind)}
                }
                card.addView(body,FrameLayout.LayoutParams(-1,-1))
                if(!plus) card.addView(TextView(this@MainActivity).apply{
                    text="×";textSize=22f;setTextColor(0xFFFF6868.toInt());gravity=Gravity.CENTER
                    contentDescription="Supprimer la catégorie $cat"
                    setOnClickListener{confirmDeleteCategoryFromCatalog(cat,video)}
                },FrameLayout.LayoutParams(dp(38),dp(38),Gravity.TOP or Gravity.END))
                return card'''
rep(old_card,new_card,"tuiles catégories")

anchor="    private fun showMoveSelected() {"
if anchor not in s:
    raise SystemExit("V0.6.35 - point d'insertion introuvable")

helpers=r'''    /** V0.6.35 : sécurité galerie, compteurs et gestion des catégories. */
    private fun returnSelectedToGallery(video:Boolean, catalog:Boolean) {
        val medias=galleryItems.filter { selected.contains(it.key) }
        if(medias.isEmpty()) return
        val edit=prefs.edit()
        medias.forEach { edit.remove("media_${it.key}") }
        if(!edit.commit()) {
            Toast.makeText(this,"Le retour vers la galerie n’a pas pu être enregistré.",Toast.LENGTH_LONG).show()
            return
        }
        selected.clear();catalogSelected.clear()
        Toast.makeText(this,"${medias.size} ${if(video)"vidéo(s)" else "image(s)"} remise(s) dans la galerie.",Toast.LENGTH_LONG).show()
        if(catalog) showCategoryCatalog(video) else {
            categoryFilter=""
            navigate(if(video)Route.VIDEOS else Route.IMAGES)
        }
    }

    private fun showCategoryOrganizer(video:Boolean) {
        val cats=savedCategories().filter { if(video) categoryScope(it)!="image" else categoryScope(it)!="video" }.toMutableList()
        if(cats.size<2) {
            Toast.makeText(this,"Il faut au moins deux catégories à organiser.",Toast.LENGTH_SHORT).show()
            return
        }
        val labels=cats.mapIndexed { i,name -> "${i+1}. $name" }.toTypedArray()
        AlertDialog.Builder(this).setTitle("Organiser les catégories")
            .setItems(labels) { _,index ->
                val name=cats[index]
                AlertDialog.Builder(this).setTitle("Déplacer « $name »")
                    .setItems(arrayOf("Monter d’une place","Descendre d’une place","Placer en premier","Placer en dernier")) { _,action ->
                        val full=savedCategories().toMutableList()
                        val old=full.indexOf(name)
                        if(old>=0) {
                            val target=when(action){0->(old-1).coerceAtLeast(0);1->(old+1).coerceAtMost(full.lastIndex);2->0;else->full.lastIndex}
                            full.removeAt(old);full.add(target,name)
                            prefs.edit().putString("category_names",full.joinToString("\u001f")).apply()
                            showCategoryCatalog(video)
                        }
                    }.setNegativeButton("Annuler",null).show()
            }.setNegativeButton("Terminer",null).show()
    }

    private fun confirmDeleteCategoryFromCatalog(category:String,video:Boolean) {
        val medias=galleryItems.filter { getCategory(it)==category }
        val word=if(video)"vidéo" else "image"
        if(medias.isEmpty()) {
            AlertDialog.Builder(this).setTitle("Supprimer cette catégorie ?")
                .setMessage("Êtes-vous sûr de vouloir supprimer cette catégorie ?\n\nCette action est définitive.")
                .setPositiveButton("Supprimer"){_,_->deleteCategoryMetadataSilent(category);showCategoryCatalog(video)}
                .setNegativeButton("Annuler",null).show()
            return
        }
        AlertDialog.Builder(this).setTitle("Supprimer cette catégorie ?")
            .setMessage("Cette catégorie contient ${medias.size} ${word}${if(medias.size>1)"s" else ""}.\n\nAttention : les ${word}s qu’elle contient seront également placées dans la corbeille Android.\n\nÊtes-vous sûr de vouloir continuer ?")
            .setPositiveButton("Supprimer"){_,_->
                if(medias.size>100) AlertDialog.Builder(this).setTitle("100 médias maximum")
                    .setMessage("Cette catégorie contient ${medias.size} médias. Déplace ou supprime d’abord une partie des médias pour revenir à 100 maximum.")
                    .setPositiveButton("Compris",null).show()
                else confirmTrashFinal(medias,category)
            }.setNegativeButton("Annuler",null).show()
    }

'''
s=s.replace(anchor,helpers+anchor,1)
p.write_text(s,encoding="utf-8")
print("V0.6.35 appliquée")
