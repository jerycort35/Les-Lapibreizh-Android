from pathlib import Path
import re
p=Path("app/src/main/java/fr/leslapibreizh/mediatheque/MainActivity.kt")
s=p.read_text(encoding="utf-8")
def rf(src,sig,new):
    a=src.find(sig)
    if a<0: raise SystemExit("Introuvable: "+sig)
    b=src.find("{",a); d=0; i=b; q=None; esc=False
    while i<len(src):
        c=src[i]
        if q:
            if esc: esc=False
            elif c=="\\": esc=True
            elif c==q: q=None
        else:
            if c in ("'",chr(34)): q=c
            elif c=="{": d+=1
            elif c=="}":
                d-=1
                if d==0: return src[:a]+new+src[i+1:]
        i+=1
    raise SystemExit("Fin introuvable")
dup=r"""    private fun showDuplicateComparison(items: List<Media>) {
        if(items.size<2){ toast("Aucun doublon exact à comparer."); return }
        var pos=1
        val content=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(9),dp(7),dp(9),dp(12)); setBackgroundColor(black) }
        fun draw(){
            content.removeAllViews()
            val a=items[0]; val b=items[pos]
            content.addView(action("‹ Retour"){loadGallery()},LinearLayout.LayoutParams(dp(100),dp(43)))
            content.addView(heading("DOUBLON DÉTECTÉ",22f))
            content.addView(note("$pos sur ${items.size-1} · fichiers strictement identiques selon SHA-256"))
            val pair=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL}
            listOf(a,b).forEachIndexed{idx,m->
                val box=LinearLayout(this).apply{
                    orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER;background=shape(Color.rgb(22,18,14),gold,11);setPadding(dp(5),dp(5),dp(5),dp(5))
                    addView(note(if(idx==0)"Fichier existant" else "Doublon"))
                    val iv=ImageView(this@MainActivity).apply{scaleType=ImageView.ScaleType.FIT_CENTER;tag=m.key;setBackgroundColor(Color.BLACK)}
                    addView(iv,LinearLayout.LayoutParams(-1,dp(205)))
                    addView(note("${m.title}\n${m.album}\n${m.size/1024} Ko"))
                    io.execute{val bm=try{thumbnail(m,720)}catch(_:Exception){null};if(bm!=null)iv.post{if(iv.tag==m.key)iv.setImageBitmap(bm)}}
                }
                pair.addView(box,LinearLayout.LayoutParams(0,-2,1f).apply{marginStart=dp(3);marginEnd=dp(3)})
            }
            content.addView(pair)
            content.addView(action("Conserver les deux"){if(pos<items.lastIndex){pos++;draw()}else{selected.clear();loadGallery()}},LinearLayout.LayoutParams(-1,dp(49)))
            content.addView(action("Sélectionner ce doublon pour la corbeille"){
                selected.clear();selected.add(b.key);toast("Doublon sélectionné. La corbeille demandera confirmation.")
                if(pos<items.lastIndex){pos++;draw()}else loadGallery()
            },LinearLayout.LayoutParams(-1,dp(49)))
            val nav=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL}
            nav.addView(action("‹ Précédent"){if(pos>1){pos--;draw()}},LinearLayout.LayoutParams(0,dp(48),1f))
            nav.addView(action(if(pos<items.lastIndex)"Suivant ›" else "Terminer"){
                if(pos<items.lastIndex){pos++;draw()}else{selected.clear();loadGallery()}
            },LinearLayout.LayoutParams(0,dp(48),1f).apply{marginStart=dp(5)})
            content.addView(nav)
            content.addView(note("Suivant permet de contrôler le doublon suivant sans modifier celui affiché."))
        }
        draw();setContentView(ScrollView(this).apply{addView(content)})
    }
"""
settings=r"""    private fun showSettings() {
        route=Route.SETTINGS;dashboardVisible=false;selected.clear()
        fun card(t:String,sub:String?=null)=LinearLayout(this).apply{
            orientation=LinearLayout.VERTICAL;setPadding(dp(11),dp(8),dp(11),dp(8));background=shape(Color.rgb(18,17,14),Color.rgb(112,86,44),12)
            addView(TextView(this@MainActivity).apply{text=t;textSize=17f;setTextColor(gold);typeface=android.graphics.Typeface.create("serif",android.graphics.Typeface.BOLD)})
            if(sub!=null)addView(TextView(this@MainActivity).apply{text=sub;textSize=11.5f;setTextColor(cream)})
        }
        fun sw(t:String,k:String,d:Boolean)=Switch(this).apply{text=t;textSize=13f;setTextColor(cream);isChecked=prefs.getBoolean(k,d);setOnCheckedChangeListener{_,v->prefs.edit().putBoolean(k,v).apply()}}
        val c=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(9),dp(6),dp(9),dp(12));setBackgroundColor(black)}
        c.addView(action("‹ Retour"){showHome()},LinearLayout.LayoutParams(dp(105),dp(43)))
        c.addView(heading("⚙  PARAMÈTRES",24f));c.addView(note("Les Lapibreizh · Nos souvenirs, notre histoire"))
        val g=card("⚙  Général","Apparence et fonctionnement")
        g.addView(note("Thème : Sombre Lapibreizh · Langue : Français"));g.addView(sw("Son et vibrations","setting_vibrations",true));g.addView(sw("Animations","setting_animations",true));c.addView(g,LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=dp(8)})
        val im=card("▣  Import des albums existants","Photos et vidéos déjà présentes sur le téléphone")
        im.addView(action("Importer depuis la galerie"){navigate(Route.IMAGES)},LinearLayout.LayoutParams(-1,dp(48)));im.addView(action("Import intelligent"){showSmartImport()},LinearLayout.LayoutParams(-1,dp(48)));c.addView(im,LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=dp(8)})
        val tr=card("✦  Tri automatique","Classement local")
        tr.addView(sw("Utiliser les noms de fichiers","setting_sort_filename",true));tr.addView(sw("Utiliser les albums d'origine","setting_sort_album",true));c.addView(tr,LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=dp(8)})
        val ty=card("▧  Types de fichiers à analyser");ty.addView(sw("Images","setting_type_images",true));ty.addView(sw("Vidéos","setting_type_videos",true));c.addView(ty,LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=dp(8)})
        val du=card("▣  Gestion des doublons","SHA-256 · photos et vidéos strictement identiques");du.addView(note("Me demander à chaque fois · aucune suppression automatique"));c.addView(du,LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=dp(8)})
        val ca=card("▣  Mes catégories","Touchez une catégorie pour la modifier")
        savedCategories().forEach{x->ca.addView(action("✎  $x"){showCategoryEditor(x)},LinearLayout.LayoutParams(-1,dp(44)))}
        ca.addView(action("＋ Ajouter une catégorie"){
            val e=EditText(this).apply{hint="Nom de la catégorie";setTextColor(cream);setHintTextColor(0xFFAAAAAA.toInt());setSingleLine(true)}
            AlertDialog.Builder(this).setTitle("Nouvelle catégorie").setView(e).setPositiveButton("Ajouter"){_,_->
                val n=e.text.toString().trim().replace("\u001f","")
                if(n.isNotEmpty()&&n.length<=40&&!savedCategories().any{it.equals(n,true)}){prefs.edit().putString("category_names",(savedCategories()+n).joinToString("\u001f")).apply();showSettings()}else toast("Nom vide, trop long ou déjà utilisé.")
            }.setNegativeButton("Annuler",null).show()
        },LinearLayout.LayoutParams(-1,dp(48)));c.addView(ca,LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=dp(8)})
        val st=card("●  Espace de stockage","Les originaux restent intacts lors d'une copie");st.addView(action("Ouvrir la médiathèque"){navigate(Route.IMAGES)},LinearLayout.LayoutParams(-1,dp(48)));c.addView(st,LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=dp(8)})
        val bk=card("↻  Sauvegarde du classement");bk.addView(action("Exporter la sauvegarde JSON"){exportCategories()},LinearLayout.LayoutParams(-1,dp(47)));bk.addView(action("Importer une sauvegarde JSON"){importCategories()},LinearLayout.LayoutParams(-1,dp(47)));c.addView(bk,LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=dp(8)})
        val ot=card("•••  Autres options");ot.addView(note("Version 0.5.3 · Noir & Or Lapibreizh\nCorbeille Android : récupérable avant expiration."));c.addView(ot)
        val screen=root().apply{setPadding(0,0,0,0)};screen.addView(ScrollView(this).apply{addView(c)},LinearLayout.LayoutParams(-1,0,1f));screen.addView(navBar(Route.SETTINGS));setContentView(screen)
    }
"""
s=rf(s,"    private fun showDuplicateComparison(items: List<Media>)",dup)
s=rf(s,"    private fun showSettings()",settings)
s=s.replace("V0.5.1 :","V0.5.3 :")
p.write_text(s,encoding="utf-8")
g=Path("app/build.gradle.kts");x=g.read_text(encoding="utf-8");x=re.sub(r"versionCode\s*=\s*\d+","versionCode = 9",x);x=re.sub(r'versionName\s*=\s*"[^"]+"','versionName = "0.5.3"',x);g.write_text(x,encoding="utf-8")
print("V0.5.3 appliquee")
