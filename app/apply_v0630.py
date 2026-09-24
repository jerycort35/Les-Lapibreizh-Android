from pathlib import Path
P=Path('app/src/main/java/fr/leslapibreizh/mediatheque/MainActivity.kt')
s=P.read_text(encoding='utf-8')

def once(a,b,label):
    global s
    n=s.count(a)
    if n != 1: raise SystemExit(f'V0.6.30 {label}: attendu 1, trouve {n}')
    s=s.replace(a,b,1)

once('''            labels.addView(simpleLabel(cat,22f,cream))
            labels.addView(simpleLabel("${allKnownMediaKeysForCategory(cat)} image(s)",12f,gold))
            identity.addView(labels,LinearLayout.LayoutParams(0,-2,1f))
            identity.addView(action("Changer de catégorie") { showImagesCategories() },LinearLayout.LayoutParams(dp(145),dp(54)))
''','''            labels.addView(simpleLabel(cat,22f,cream))
            labels.addView(simpleLabel("${allKnownMediaKeysForCategory(cat)} image(s)",12f,cream))
            labels.addView(simpleLabel("Apprendre  •  Comprendre  •  Protéger  •  Partager",11f,gold))
            identity.addView(labels,LinearLayout.LayoutParams(0,-2,1f))
            identity.addView(action("▰  Changer\\nde catégorie") { showImagesCategories() },LinearLayout.LayoutParams(dp(155),dp(64)))
''','identite')

once('''        val info = note("Chargement des médias…")
        selectionInfo = info
        layout.addView(info)
''','''        val info = note("Chargement des médias…")
        selectionInfo = info
        info.visibility = View.GONE
''','compteur')

once('''        layout.addView(search, LinearLayout.LayoutParams(-1, dp(52)).apply {
            bottomMargin = dp(7)
        })
        val isImageCategory = route == Route.IMAGES && categoryFilter != null && !categoryFilter.isNullOrBlank()
        if (isImageCategory) {
            val displayOptions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
''','''        val isImageCategory = route == Route.IMAGES && categoryFilter != null && !categoryFilter.isNullOrBlank()
        if (isImageCategory) {
            search.hint = "⌕  Rechercher une image…"
            val searchRow = LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL }
            searchRow.addView(search, LinearLayout.LayoutParams(0,dp(56),1f).apply { rightMargin=dp(5) })
            searchRow.addView(action("☷  Filtres") { openFilters() },LinearLayout.LayoutParams(dp(105),dp(56)))
            layout.addView(searchRow,LinearLayout.LayoutParams(-1,dp(56)).apply { bottomMargin=dp(4) })
            layout.addView(simpleLabel("Taille des miniatures",12f,cream).apply { gravity=Gravity.END })
            val displayOptions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
''','recherche image')

once('''        } else {
            val filters = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
''','''        } else {
            layout.addView(search, LinearLayout.LayoutParams(-1, dp(52)).apply { bottomMargin=dp(7) })
            val filters = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
''','recherche autres')

start=s.index('        selectionActions = LinearLayout(this).apply {')
end=s.index('        val g = GridView(this).apply {',start)
new='''        selectionActions = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = if (isImageCategory) View.VISIBLE else View.GONE
        }.also { actions ->
            val r1=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL }
            r1.addView(action("ⓧ  0 image\\nsélectionnée") { selected.clear(); updateSelection() },LinearLayout.LayoutParams(0,dp(56),1f))
            r1.addView(action("Partager\\nChatGPT") { shareSelected() },LinearLayout.LayoutParams(0,dp(56),1f))
            r1.addView(action("Partager\\nInstagram") { shareSelected() },LinearLayout.LayoutParams(0,dp(56),1f))
            r1.addView(action("Partager\\nFacebook") { shareSelected() },LinearLayout.LayoutParams(0,dp(56),1f))
            r1.addView(action("Plus de\\npartages") { shareSelected() },LinearLayout.LayoutParams(0,dp(56),1f))
            actions.addView(r1)
            val r2=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL }
            r2.addView(action("▰  Déplacer\\n(0 / 1000)") { showMoveSelected() },LinearLayout.LayoutParams(0,dp(56),1f))
            r2.addView(dangerAction("Supprimer\\n(0 / 100 max)") { showDeleteSelected() },LinearLayout.LayoutParams(0,dp(56),1f))
            r2.addView(action("☑  Tout sélectionner") {
                selected.clear(); shownItems.take(1000).forEach { selected.add(it.key) }; updateSelection()
            },LinearLayout.LayoutParams(0,dp(56),1f))
            actions.addView(r2)
            layout.addView(actions)
        }
'''
s=s[:start]+new+s[end:]
s=s.replace('addView(simpleLabel("▣",54f,gold).apply { gravity=Gravity.CENTER })',
            'addView(simpleLabel("▧",58f,gold).apply { gravity=Gravity.CENTER })',1)
P.write_text(s,encoding='utf-8')
print('V0.6.30 applique')
