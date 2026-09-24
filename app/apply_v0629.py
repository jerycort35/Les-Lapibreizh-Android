from pathlib import Path

P=Path('app/src/main/java/fr/leslapibreizh/mediatheque/MainActivity.kt')
s=P.read_text(encoding='utf-8')

def once(old,new,label):
    global s
    n=s.count(old)
    if n!=1:
        raise SystemExit(f'PATCH V0.6.29: {label}: motif attendu 1 fois, trouve {n}')
    s=s.replace(old,new,1)

# Etat vide dynamique.
once('    private var selectionInfo: TextView? = null\n',
     '    private var selectionInfo: TextView? = null\n    private var galleryEmptyState: View? = null\n', 'champ empty state')

# Catalogue : bandeau plus compact et vraie zone Retour cliquable.
once('''        content.addView(ImageView(this).apply {
            setImageResource(R.drawable.art_images_categories_banner)
            scaleType=ImageView.ScaleType.FIT_XY
            adjustViewBounds=true
            contentDescription="Les Lapibreizh · Médiathèque Images"
        }, LinearLayout.LayoutParams(-1,dp(305)))
''','''        val catalogHeader = FrameLayout(this).apply {
            val art = ImageView(this@MainActivity).apply {
                setImageResource(R.drawable.art_images_categories_banner)
                scaleType = ImageView.ScaleType.FIT_XY
                contentDescription = "Les Lapibreizh · Médiathèque Images"
            }
            addView(art, FrameLayout.LayoutParams(-1, -1))
            addView(View(this@MainActivity).apply {
                isClickable = true
                isFocusable = true
                contentDescription = "Retour"
                setOnClickListener { showHome() }
            }, FrameLayout.LayoutParams(dp(112), dp(66), Gravity.START or Gravity.TOP))
        }
        content.addView(catalogHeader, LinearLayout.LayoutParams(-1,dp(205)))
''','header catalogue')

# Grille : Nouvelle catégorie en PREMIER, puis catégories, puis À classer.
start=s.index('        categories.forEach { c -> grid.addView(tile(c)')
end_marker='        grid.addView(plus,GridLayout.LayoutParams().apply{width=0;height=dp(160);columnSpec=GridLayout.spec(GridLayout.UNDEFINED,1f);setMargins(dp(3),dp(3),dp(3),dp(3))})\n'
end=s.index(end_marker,start)+len(end_marker)
replacement='''        val plus=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER
            background=GradientDrawable().apply{setColor(Color.rgb(48,31,12));setStroke(dp(2),gold)}
            addView(simpleLabel("+",52f,gold).apply{gravity=Gravity.CENTER},LinearLayout.LayoutParams(-1,dp(100)))
            addView(simpleLabel("Nouvelle\\ncatégorie",13f,gold).apply{gravity=Gravity.CENTER},LinearLayout.LayoutParams(-1,dp(50)))
            setOnClickListener { addCategoryDialog() }
        }
        grid.addView(plus,GridLayout.LayoutParams().apply{width=0;height=dp(160);columnSpec=GridLayout.spec(GridLayout.UNDEFINED,1f);setMargins(dp(3),dp(3),dp(3),dp(3))})
        categories.forEach { c -> grid.addView(tile(c),GridLayout.LayoutParams().apply { width=0;height=dp(160);columnSpec=GridLayout.spec(GridLayout.UNDEFINED,1f);setMargins(dp(3),dp(3),dp(3),dp(3)) }) }
        val unsorted=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER
            background=GradientDrawable().apply{setColor(Color.rgb(18,18,17));setStroke(dp(1),gold)}
            addView(simpleLabel("?",52f,gold).apply{gravity=Gravity.CENTER},LinearLayout.LayoutParams(-1,dp(105)))
            addView(simpleLabel("À classer",13f,cream).apply{gravity=Gravity.CENTER},LinearLayout.LayoutParams(-1,dp(29)))
            setOnClickListener { navigate(Route.UNSORTED) }
        }
        grid.addView(unsorted,GridLayout.LayoutParams().apply{width=0;height=dp(160);columnSpec=GridLayout.spec(GridLayout.UNDEFINED,1f);setMargins(dp(3),dp(3),dp(3),dp(3))})
'''
s=s[:start]+replacement+s[end:]

# Seule la zone catégories défile. IMPORTANT : FrameLayout.LayoutParams compile avec ScrollView.
once('''        content.addView(grid,LinearLayout.LayoutParams(-1,-2))
''','''        val categoryScroll = ScrollView(this).apply {
            isFillViewport = false
            setBackgroundColor(black)
            addView(grid, FrameLayout.LayoutParams(-1, -2))
        }
        content.addView(categoryScroll,LinearLayout.LayoutParams(-1,0,1f))
''','scroll categories')

# Le contenu complet ne défile plus : actions + nav restent fixes.
once('''        val scroll=ScrollView(this).apply { setBackgroundColor(black); addView(content) }
        page.addView(scroll,LinearLayout.LayoutParams(-1,0,1f))
        page.addView(navBar(Route.IMAGES),LinearLayout.LayoutParams(-1,dp(75)))
''','''        page.addView(content,LinearLayout.LayoutParams(-1,0,1f))
        page.addView(navBar(Route.IMAGES),LinearLayout.LayoutParams(-1,dp(75)))
''','page catalogue fixe')

# Dans une catégorie, la barre d'actions n'occupe pas de place tant qu'il n'y a aucune sélection.
once('''            visibility = if (route == Route.IMAGES && categoryFilter != null && !categoryFilter.isNullOrBlank()) View.VISIBLE else View.GONE
''','''            visibility = View.GONE
''','actions selection')

# Supprime l'ancien empty-state qui partageait mal la hauteur.
start=s.index('        if (route == Route.IMAGES && categoryFilter != null && shownItems.isEmpty()) {\n            val empty = LinearLayout(this).apply {')
end=s.index('        galleryAdapter = object : BaseAdapter() {',start)
s=s[:start]+s[end:]

# Une seule zone centrale : grille défilante OU état vide, sur toute la hauteur restante.
once('''        layout.addView(g, LinearLayout.LayoutParams(-1, 0, 1f))
        val more = action("Afficher 120 éléments supplémentaires") {
''','''        val mediaArea = FrameLayout(this).apply {
            setBackgroundColor(black)
            background = GradientDrawable().apply { setColor(black); setStroke(dp(1), Color.rgb(83,67,38)) }
        }
        mediaArea.addView(g, FrameLayout.LayoutParams(-1, -1))
        val emptyState = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(18), dp(18), dp(18), dp(18))
            visibility = View.GONE
            addView(simpleLabel("▣",54f,gold).apply { gravity=Gravity.CENTER })
            addView(simpleLabel("Aucune image dans cette catégorie",20f,cream).apply { gravity=Gravity.CENTER })
            addView(simpleLabel("Appuyez sur « Nouvelle image » pour ajouter des images depuis votre galerie.",13f,cream).apply { gravity=Gravity.CENTER })
            addView(action("＋  Nouvelle image") { pickImagesForCategory() }, LinearLayout.LayoutParams(dp(230),dp(58)).apply { topMargin=dp(14) })
        }
        galleryEmptyState = emptyState
        mediaArea.addView(emptyState, FrameLayout.LayoutParams(-1, -1))
        layout.addView(mediaArea, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin=dp(4); bottomMargin=dp(4) })
        val more = action("Afficher 120 éléments supplémentaires") {
''','zone media')

# Etat vide mis à jour APRES la vraie lecture MediaStore.
once('''        galleryAdapter?.notifyDataSetChanged(); refreshInfo()
        (grid?.parent as? LinearLayout)?.findViewWithTag<Button>("more")?.visibility = if (shownItems.size > visibleLimit) View.VISIBLE else View.GONE
''','''        galleryAdapter?.notifyDataSetChanged(); refreshInfo()
        val imageCategory = route == Route.IMAGES && categoryFilter != null && !categoryFilter.isNullOrBlank()
        val empty = imageCategory && shownItems.isEmpty()
        galleryEmptyState?.visibility = if (empty) View.VISIBLE else View.GONE
        grid?.visibility = if (empty) View.GONE else View.VISIBLE
        val galleryLayout = grid?.parent?.parent as? LinearLayout
        galleryLayout?.findViewWithTag<Button>("more")?.visibility = if (!empty && shownItems.size > visibleLimit) View.VISIBLE else View.GONE
''','update empty')

# En Personnalisé, l'appui long déplace : le texte l'indique clairement.
once('''        selectionInfo?.text = if (selected.isEmpty()) "$count / ${shownItems.size} médias · appui long pour sélectionner"
            else "${selected.size} sélectionné(s) · $count / ${shownItems.size} médias"
''','''        selectionInfo?.text = if (selected.isEmpty()) {
            if (route == Route.IMAGES && categoryFilter != null && !categoryFilter.isNullOrBlank() && mediaSort == 0)
                "$count / ${shownItems.size} images · appui long pour déplacer"
            else "$count / ${shownItems.size} médias · appui long pour sélectionner"
        } else "${selected.size} sélectionné(s) · $count / ${shownItems.size} médias"
''','texte appui long')

once('''        more.tag = "more"
        layout.addView(more, LinearLayout.LayoutParams(-1, dp(45)))
''','''        more.tag = "more"
        more.visibility = View.GONE
        layout.addView(more, LinearLayout.LayoutParams(-1, dp(45)))
''','bouton more')

P.write_text(s,encoding='utf-8')
print('V0.6.29 appliquee : compilation + visuels Images/Categories + zones centrales defilantes + Retour.')
