from pathlib import Path
import re

P=Path('app/src/main/java/fr/leslapibreizh/mediatheque/MainActivity.kt')
s=P.read_text(encoding='utf-8')
orig=s

def once(old,new,label):
    global s
    n=s.count(old)
    if n!=1:
        raise SystemExit(f'PATCH V0.6.28: {label}: motif attendu 1 fois, trouve {n}')
    s=s.replace(old,new,1)

# 1. Etat de la zone vide dynamique.
once('    private var selectionInfo: TextView? = null\n',
     '    private var selectionInfo: TextView? = null\n    private var galleryEmptyState: View? = null\n', 'champ empty state')

# 2. Catalogue Images : bandeau compact + vraie zone Retour cliquable.
old='''        content.addView(ImageView(this).apply {\n            setImageResource(R.drawable.art_images_categories_banner)\n            scaleType=ImageView.ScaleType.FIT_XY\n            adjustViewBounds=true\n            contentDescription="Les Lapibreizh · Médiathèque Images"\n        }, LinearLayout.LayoutParams(-1,dp(305)))\n'''
new='''        val catalogHeader = FrameLayout(this).apply {\n            val art = ImageView(this@MainActivity).apply {\n                setImageResource(R.drawable.art_images_categories_banner)\n                scaleType = ImageView.ScaleType.FIT_XY\n                contentDescription = "Les Lapibreizh · Médiathèque Images"\n            }\n            addView(art, FrameLayout.LayoutParams(-1, -1))\n            addView(View(this@MainActivity).apply {\n                isClickable = true\n                isFocusable = true\n                contentDescription = "Retour"\n                setOnClickListener { showHome() }\n            }, FrameLayout.LayoutParams(dp(110), dp(62), Gravity.START or Gravity.TOP))\n        }\n        content.addView(catalogHeader, LinearLayout.LayoutParams(-1,dp(210)))\n'''
once(old,new,'header catalogue')

# 3. Seule la grille des catégories défile.
once('''        content.addView(grid,LinearLayout.LayoutParams(-1,-2))\n''','''        val categoryScroll = ScrollView(this).apply {\n            isFillViewport = false\n            setBackgroundColor(black)\n            addView(grid, ScrollView.LayoutParams(-1, -2))\n        }\n        content.addView(categoryScroll,LinearLayout.LayoutParams(-1,0,1f))\n''','scroll categories')

once('''        val scroll=ScrollView(this).apply { setBackgroundColor(black); addView(content) }\n        page.addView(scroll,LinearLayout.LayoutParams(-1,0,1f))\n        page.addView(navBar(Route.IMAGES),LinearLayout.LayoutParams(-1,dp(75)))\n''','''        page.addView(content,LinearLayout.LayoutParams(-1,0,1f))\n        page.addView(navBar(Route.IMAGES),LinearLayout.LayoutParams(-1,dp(75)))\n''','page catalogue fixe')

# 4. Galerie : supprimer l'ancien empty-state qui prenait la moitié de l'écran.
start='''        if (route == Route.IMAGES && categoryFilter != null && shownItems.isEmpty()) {\n            val empty = LinearLayout(this).apply {'''
a=s.find(start)
if a<0: raise SystemExit('PATCH V0.6.28: ancien empty state introuvable')
b=s.find('        galleryAdapter = object : BaseAdapter() {',a)
if b<0: raise SystemExit('PATCH V0.6.28: adapter introuvable')
s=s[:a]+s[b:]

# 5. Galerie : Grid + état vide superposés dans UNE zone centrale pondérée/défilante.
once('''        layout.addView(g, LinearLayout.LayoutParams(-1, 0, 1f))\n        val more = action("Afficher 120 éléments supplémentaires") {\n''','''        val mediaArea = FrameLayout(this).apply { setBackgroundColor(black) }\n        mediaArea.addView(g, FrameLayout.LayoutParams(-1, -1))\n        val emptyState = LinearLayout(this).apply {\n            orientation = LinearLayout.VERTICAL\n            gravity = Gravity.CENTER\n            setPadding(dp(18), dp(24), dp(18), dp(24))\n            visibility = View.GONE\n            addView(simpleLabel("▣",58f,gold).apply { gravity=Gravity.CENTER })\n            addView(simpleLabel("Aucune image dans cette catégorie",20f,cream).apply { gravity=Gravity.CENTER })\n            addView(simpleLabel("Appuyez sur « Nouvelle image » pour ajouter des images depuis votre galerie.",13f,cream).apply { gravity=Gravity.CENTER })\n            addView(action("＋  Nouvelle image") { pickImagesForCategory() }, LinearLayout.LayoutParams(dp(230),dp(58)).apply { topMargin=dp(18) })\n        }\n        galleryEmptyState = emptyState\n        mediaArea.addView(emptyState, FrameLayout.LayoutParams(-1, -1))\n        layout.addView(mediaArea, LinearLayout.LayoutParams(-1, 0, 1f))\n        val more = action("Afficher 120 éléments supplémentaires") {\n''','zone media')

# 6. Mise à jour dynamique de l'état vide après le vrai chargement MediaStore.
once('''        galleryAdapter?.notifyDataSetChanged(); refreshInfo()\n        (grid?.parent as? LinearLayout)?.findViewWithTag<Button>("more")?.visibility = if (shownItems.size > visibleLimit) View.VISIBLE else View.GONE\n''','''        galleryAdapter?.notifyDataSetChanged(); refreshInfo()\n        val imageCategory = route == Route.IMAGES && categoryFilter != null && !categoryFilter.isNullOrBlank()\n        val empty = imageCategory && shownItems.isEmpty()\n        galleryEmptyState?.visibility = if (empty) View.VISIBLE else View.GONE\n        grid?.visibility = if (empty) View.GONE else View.VISIBLE\n        val parent = grid?.parent?.parent as? LinearLayout\n        parent?.findViewWithTag<Button>("more")?.visibility = if (!empty && shownItems.size > visibleLimit) View.VISIBLE else View.GONE\n''','update empty')

# 7. Message cohérent : appui long = déplacement en Personnalisé.
once('''        selectionInfo?.text = if (selected.isEmpty()) "$count / ${shownItems.size} médias · appui long pour sélectionner"\n            else "${selected.size} sélectionné(s) · $count / ${shownItems.size} médias"\n''','''        selectionInfo?.text = if (selected.isEmpty()) {\n            if (route == Route.IMAGES && categoryFilter != null && !categoryFilter.isNullOrBlank() && mediaSort == 0)\n                "$count / ${shownItems.size} images · appui long pour déplacer"\n            else "$count / ${shownItems.size} médias · appui long pour sélectionner"\n        } else "${selected.size} sélectionné(s) · $count / ${shownItems.size} médias"\n''','texte appui long')

# 8. Le bouton Plus doit être caché pendant le chargement/si vide.
once('''        more.tag = "more"\n        layout.addView(more, LinearLayout.LayoutParams(-1, dp(45)))\n''','''        more.tag = "more"\n        more.visibility = View.GONE\n        layout.addView(more, LinearLayout.LayoutParams(-1, dp(45)))\n''','bouton more')

P.write_text(s,encoding='utf-8')

print('V0.6.28 appliquee : zones defilantes + page Images pleine hauteur + Retour catalogue.')
