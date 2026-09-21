# Les Lapibreizh — La Médiathèque V0.5.0

Base Android propre de la médiathèque Les Lapibreizh.

- `applicationId`: `fr.leslapibreizh.mediatheque`
- `versionCode`: `6`
- `versionName`: `0.5.0`
- Android 8+ (`minSdk 26`)
- compilation cible Android 15 (`compileSdk/targetSdk 35`)
- Java 17 / Kotlin
- build GitHub Actions : **Release signé uniquement**

La V0.5 conserve les fonctions déjà opérationnelles : lecture MediaStore, images/vidéos, recherche, catégories, sélection, favoris, tris, détection SHA-256 des doublons exacts, copie via SAF, corbeille Android avec confirmation, sauvegarde/restauration JSON.

Les 11 maquettes validées servent de direction graphique et fonctionnelle. Les fonctions non encore réellement développées ne sont pas simulées par de faux boutons.

Voir `GUIDE_V050.txt` pour la signature et l'installation.
