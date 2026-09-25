from pathlib import Path

p=Path('app/src/main/java/fr/leslapibreizh/mediatheque/MainActivity.kt')
s=p.read_text(encoding='utf-8')
MARKER='    /** V0.6.36 : cadrage fidèle et validation explicite des images de catégorie. */'
if MARKER in s:
    print('V0.6.36 déjà appliquée')
    raise SystemExit(0)

def rep(old,new,label):
    global s
    if old not in s:
        raise SystemExit('V0.6.36 - bloc introuvable : '+label)
    s=s.replace(old,new,1)

# 1. Remove label that collides with Organiser, keep the three size controls.
rep('        text("Taille des miniatures",610,431,234,29,18f)\n','', 'libellé taille miniatures')

# 2. Pending category image state: nothing is persisted before Enregistrer.
rep('    private var pendingCategoryImage: String? = null\n', '''    private var pendingCategoryImage: String? = null
    /** V0.6.36 : cadrage fidèle et validation explicite des images de catégorie. */
    private var pendingCategoryImagePath: String? = null
    private var pendingCategoryImageOwner: String? = null
''', 'état image temporaire')

# 3. Avoid a second crop of an already-cropped category image.
rep('''    private fun currentCategoryPhoto(category: String): ImageView = ImageView(this).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        contentDescription = "Photographie de la catégorie $category"
        val path = prefs.getString("category_image_$category", null)
''','''    private fun currentCategoryPhoto(category: String): ImageView = ImageView(this).apply {
        scaleType = ImageView.ScaleType.FIT_CENTER
        contentDescription = "Photographie de la catégorie $category"
        val pending = pendingCategoryImagePath?.takeIf { pendingCategoryImageOwner == category }
        val path = pending ?: prefs.getString("category_image_$category", null)
''','affichage cadrage')

# 4. Editor: save is the only commit point; changing image no longer auto-saves category.
old='''        fun save():String?{val value=name.text.toString().trim().replace("\\u001f","");return if(saveCategoryChanges(category,value,picked))value else null}
        page.put(page.button("Enregistrer",22f){save()?.let{Toast.makeText(this,"Catégorie enregistrée",Toast.LENGTH_SHORT).show();showCategoryEditor(it)}},687,48,153,60)
        page.put(currentCategoryPhoto(category),25,319,250,230)
        page.put(page.button("▣  Changer l’image",21f){
            val saved=save();if(saved!=null){pendingCategoryImage=saved
                startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply{type="image/*";addCategory(Intent.CATEGORY_OPENABLE);addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)},305)
            }
        },30,489,240,54)
'''
new='''        fun save():String?{
            val value=name.text.toString().trim().replace("\\u001f","")
            if(!saveCategoryChanges(category,value,picked)) return null
            val staged=pendingCategoryImagePath?.takeIf { pendingCategoryImageOwner==category }
            if(staged!=null){
                val stagedFile=File(staged)
                val dir=File(filesDir,"category_images").apply{mkdirs()}
                val finalFile=File(dir,"category_${System.currentTimeMillis()}.jpg")
                val copied=try{stagedFile.copyTo(finalFile,overwrite=true);finalFile.exists()&&finalFile.length()>0L}catch(_:Exception){false}
                if(!copied){finalFile.delete();Toast.makeText(this,"L’image n’a pas pu être enregistrée",Toast.LENGTH_LONG).show();return null}
                val oldPath=prefs.getString("category_image_$value",null)
                if(!prefs.edit().putString("category_image_$value",finalFile.absolutePath).commit()){
                    finalFile.delete();Toast.makeText(this,"L’image n’a pas pu être enregistrée",Toast.LENGTH_LONG).show();return null
                }
                if(!oldPath.isNullOrBlank() && oldPath!=finalFile.absolutePath) try{File(oldPath).takeIf{it.exists()&&it.parentFile==dir}?.delete()}catch(_:Exception){}
                stagedFile.delete();pendingCategoryImagePath=null;pendingCategoryImageOwner=null
            }
            return value
        }
        page.put(page.button("Enregistrer",22f){save()?.let{Toast.makeText(this,"Catégorie enregistrée",Toast.LENGTH_SHORT).show();showCategoryEditor(it)}},687,48,153,60)
        page.put(currentCategoryPhoto(category),25,319,250,230)
        page.put(page.button("▣  Changer l’image",21f){
            pendingCategoryImage=category
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply{type="image/*";addCategory(Intent.CATEGORY_OPENABLE);addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)},305)
        },30,489,240,54)
'''
rep(old,new,'éditeur catégorie')

# 5. Leaving editor without saving discards the staged image.
rep('''        val page=managementPage(R.drawable.manage_category_master,"⚙  Modifier une catégorie","Personnalise ta catégorie selon tes envies","Mes catégories"){showCategoryManager(videoCategoryTab,categoryEntry)}
''','''        val page=managementPage(R.drawable.manage_category_master,"⚙  Modifier une catégorie","Personnalise ta catégorie selon tes envies","Mes catégories"){
            pendingCategoryImagePath?.let{try{File(it).delete()}catch(_:Exception){}}
            pendingCategoryImagePath=null;pendingCategoryImageOwner=null;pendingCategoryImage=null
            showCategoryManager(videoCategoryTab,categoryEntry)
        }
''','retour éditeur')

# 6. Cropper is square on screen, exactly like the exported 900x900 image.
rep('''        val cropView=CategoryCropView(this,bitmap)
        layout.addView(cropView,LinearLayout.LayoutParams(-1,dp(360)))
''','''        val cropView=CategoryCropView(this,bitmap)
        val cropSide=(resources.displayMetrics.widthPixels-dp(24)).coerceAtLeast(dp(240))
        layout.addView(cropView,LinearLayout.LayoutParams(-1,cropSide))
''','géométrie cadrage')

# 7. Crop action creates a staged file only. Enregistrer in editor performs persistence.
old='''            val dir=File(filesDir,"category_images").apply { mkdirs() }
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
'''
new='''            val dir=File(filesDir,"category_images").apply { mkdirs() }
            pendingCategoryImagePath?.let{try{File(it).delete()}catch(_:Exception){}}
            val target=File(dir,"pending_category_${System.currentTimeMillis()}.jpg")
            val ok=try { FileOutputStream(target).use { cropped.compress(Bitmap.CompressFormat.JPEG,90,it) } } catch (_:Exception) { false }
            if (!ok || !target.exists() || target.length()==0L) {
                target.delete(); Toast.makeText(this,"Échec de la copie interne",Toast.LENGTH_LONG).show(); return@primaryAction
            }
            pendingCategoryImagePath=target.absolutePath
            pendingCategoryImageOwner=category
            pendingCategoryImage=null
            Toast.makeText(this,"Aperçu prêt · appuie sur Enregistrer pour valider",Toast.LENGTH_SHORT).show()
            showCategoryEditor(category)
'''
rep(old,new,'validation différée image')

# 8. Cancelling cropper must not destroy a previously staged image; it just returns.
rep('''        layout.addView(action("Annuler") { pendingCategoryImage=null; showCategoryEditor(category) },LinearLayout.LayoutParams(-1,dp(54)).apply { topMargin=dp(6) })
''','''        layout.addView(action("Annuler") { pendingCategoryImage=null; showCategoryEditor(category) },LinearLayout.LayoutParams(-1,dp(54)).apply { topMargin=dp(6) })
''','annulation cadrage')

p.write_text(s,encoding='utf-8')
print('V0.6.36 appliquée')
