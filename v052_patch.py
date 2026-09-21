#!/usr/bin/env python3
from pathlib import Path

p=Path("app/src/main/java/fr/leslapibreizh/mediatheque/MainActivity.kt")
s=p.read_text(encoding="utf-8")

def fun_replace(src, sig, body):
    a=src.find(sig)
    if a<0: raise RuntimeError("Introuvable: "+sig)
    b=src.find("{",a); d=0
    for i in range(b,len(src)):
        d += (src[i]=="{")-(src[i]=="}")
        if d==0: return src[:a]+body+src[i+1:]
    raise RuntimeError("Accolades invalides")

s=s.replace("* V0.5.1 :", "* V0.5.2 :")
s=s.replace("private var sortButton: Button? = null","private var sortButton: TextView? = null")
s=s.replace("private var duplicateButton: Button? = null","private var duplicateButton: TextView? = null")
s=s.replace("Version 0.5.1 · Grandes vignettes HD · doublons photos + vidéos",
            "Version 0.5.2 · interface noir & or · doublons photos + vidéos")

s=fun_replace(s,"    private fun action(text: String, onClick: () -> Unit)",
'''    private fun action(text: String, onClick: () -> Unit) = TextView(this).apply {
        this.text=text; textSize=13.5f; gravity=Gravity.CENTER
        typeface=android.graphics.Typeface.create("serif",android.graphics.Typeface.BOLD)
        setTextColor(cream)
        background=GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(Color.rgb(58,44,24),Color.rgb(14,12,9))).apply {
            cornerRadius=dp(10).toFloat(); setStroke(dp(1),gold)
        }
        elevation=dp(4).toFloat(); setPadding(dp(12),dp(7),dp(12),dp(7)); minHeight=dp(46)
        isClickable=true; isFocusable=true; setOnClickListener { onClick() }
    }''')

s=fun_replace(s,"    private fun showDuplicateComparison(items: List<Media>)",
'''    private fun showDuplicateComparison(items: List<Media>) {
        val all=items.distinctBy { it.key }
        if(all.size<2){ toast("Aucun doublon exact."); return }
        var pos=1
        fun draw(){
            val a=all[0]; val b=all[pos]
            val c=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(9),dp(7),dp(9),dp(12)); setBackgroundColor(black) }
            c.addView(action("‹ Retour"){loadGallery()},LinearLayout.LayoutParams(dp(105),dp(46)))
            c.addView(heading("DOUBLON DÉTECTÉ",22f))
            c.addView(note("$pos sur ${all.size-1} · identité vérifiée par SHA-256"))
            val row=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL}
            listOf(a,b).forEachIndexed { n,m ->
                val box=LinearLayout(this).apply{
                    orientation=LinearLayout.VERTICAL; gravity=Gravity.CENTER
                    background=shape(Color.rgb(22,18,14),gold,11); setPadding(dp(4),dp(4),dp(4),dp(4))
                    addView(note(if(n==0)"FICHIER DE RÉFÉRENCE" else "DOUBLON"))
                    val iv=ImageView(this@MainActivity).apply{scaleType=ImageView.ScaleType.FIT_CENTER;tag=m.key;setBackgroundColor(Color.BLACK)}
                    addView(iv,LinearLayout.LayoutParams(-1,dp(205))); addView(note("${m.title}\\n${m.album}\\n${m.size/1024} Ko"))
                    io.execute{val bm=try{thumbnail(m,720)}catch(_:Exception){null};if(bm!=null)iv.post{if(iv.tag==m.key)iv.setImageBitmap(bm)}}
                }
                row.addView(box,LinearLayout.LayoutParams(0,-2,1f).apply{marginStart=dp(2);marginEnd=dp(2)})
            }
            c.addView(row)
            val nav=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL}
            nav.addView(action("‹ Précédent"){if(pos>1){pos--;draw()}},LinearLayout.LayoutParams(0,dp(48),1f).apply{marginEnd=dp(3)})
            nav.addView(action("Suivant ›"){if(pos<all.lastIndex){pos++;draw()}},LinearLayout.LayoutParams(0,dp(48),1f).apply{marginStart=dp(3)})
            c.addView(nav,LinearLayout.LayoutParams(-1,dp(50)).apply{topMargin=dp(6)})
            c.addView(action("Mettre ce doublon à la corbeille"){showTrashConfirmation(listOf(b))},LinearLayout.LayoutParams(-1,dp(52)).apply{topMargin=dp(6)})
            c.addView(action("Conserver les deux et continuer"){if(pos<all.lastIndex){pos++;draw()}else loadGallery()},LinearLayout.LayoutParams(-1,dp(50)).apply{topMargin=dp(5)})
            setContentView(ScrollView(this).apply{addView(c)})
        }; draw()
    }''')

s=fun_replace(s,"    private fun showTrashConfirmation(files: List<Media>)",
'''    private fun showTrashConfirmation(files: List<Media>) {
        val old=route
        val c=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(10),dp(8),dp(10),dp(14));setBackgroundColor(black)}
        c.addView(action("‹ Annuler"){route=old;loadGallery()},LinearLayout.LayoutParams(dp(105),dp(46)))
        c.addView(heading("SUPPRIMER DES MÉDIAS",22f))
        val bytes=files.sumOf{it.size}; c.addView(note("${files.size} élément(s) · ${"%.1f".format(bytes/1048576.0)} Mo"))
        val pics=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL}
        files.take(4).forEach{m->
            val iv=ImageView(this).apply{scaleType=ImageView.ScaleType.CENTER_CROP;tag=m.key;setBackgroundColor(Color.BLACK)}
            pics.addView(iv,LinearLayout.LayoutParams(0,dp(105),1f).apply{marginStart=dp(2);marginEnd=dp(2)})
            io.execute{val bm=try{thumbnail(m,480)}catch(_:Exception){null};if(bm!=null)iv.post{if(iv.tag==m.key)iv.setImageBitmap(bm)}}
        }
        c.addView(pics,LinearLayout.LayoutParams(-1,dp(110)))
        if(files.size>4)c.addView(note("+ ${files.size-4} autre(s)"))
        c.addView(note("⚠ Mise à la corbeille Android : les fichiers restent généralement récupérables jusqu’à expiration. Ce n’est pas un effacement sécurisé.").apply{background=shape(Color.rgb(48,18,18),Color.rgb(150,55,55),10)})
        val check=CheckBox(this).apply{text="Je confirme la mise à la corbeille";setTextColor(cream)}
        c.addView(check)
        c.addView(action("Mettre à la corbeille Android"){
            if(!check.isChecked){toast("Coche d’abord la confirmation.");return@action}
            if(Build.VERSION.SDK_INT<30){toast("Android 11 minimum.");route=old;loadGallery()}
            else{pendingTrash=files;try{val x=MediaStore.createTrashRequest(contentResolver,files.map{it.uri},true);startIntentSenderForResult(x.intentSender,trashRequest,null,0,0,0)}
            catch(_:Exception){pendingTrash=emptyList();toast("Aucun fichier modifié.");route=old;loadGallery()}}
        },LinearLayout.LayoutParams(-1,dp(55)).apply{topMargin=dp(7)})
        setContentView(ScrollView(this).apply{addView(c)})
    }''')

p.write_text(s,encoding="utf-8")
g=Path("app/build.gradle.kts")
t=g.read_text(encoding="utf-8").replace('versionCode = 7','versionCode = 8').replace('versionName = "0.5.1"','versionName = "0.5.2"')
g.write_text(t,encoding="utf-8")
print("Patch V0.5.2 appliqué")
