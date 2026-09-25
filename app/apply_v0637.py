from pathlib import Path

p=Path("app/src/main/java/fr/leslapibreizh/mediatheque/MainActivity.kt")
s=p.read_text(encoding="utf-8")
marker="    /** V0.6.37 : la miniature remplit toute sa zone sans nouveau recadrage. */"
if marker in s:
    print("V0.6.37 déjà appliquée")
    raise SystemExit(0)

old = '    private fun currentCategoryPhoto(category: String): ImageView = ImageView(this).apply {\n        scaleType = ImageView.ScaleType.FIT_CENTER\n'
new = '    /** V0.6.37 : la miniature remplit toute sa zone sans nouveau recadrage. */\n    private fun currentCategoryPhoto(category: String): ImageView = ImageView(this).apply {\n        scaleType = ImageView.ScaleType.FIT_XY\n'
if old not in s:
    raise SystemExit("V0.6.37 - currentCategoryPhoto introuvable")
s=s.replace(old,new,1)
p.write_text(s,encoding="utf-8")
print("V0.6.37 appliquée")
