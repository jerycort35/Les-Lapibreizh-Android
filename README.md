# Les Lapibreizh — La Médiathèque

Version 0.2 — essai de galerie Android. Projet à compiler et tester sur l'appareil avant diffusion.

## Inclus

- Accueil avec icône Lapibreizh validée.
- Photos et vidéos lues depuis Android MediaStore (miniatures, ouverture via application du téléphone).
- Filtres par album et par catégorie, tri chronologique ou alphabétique.
- Sélection multiple par appui long, partage et attribution de catégories locales.
- Catégories ajoutables dans Paramètres.
- Lecture seule : aucun déplacement ou effacement de fichier, aucune analyse automatique.

## Limites importantes

- Compilation et installation **non testées localement** ; lancer le workflow GitHub Actions.
- Le classement interne est lié à cet appareil et à cette installation ; désinstaller l'application efface ses catégories locales.
- Les médias auxquels Android n'a pas accordé l'accès ne seront pas affichés.
- Ne prétend pas remplacer la future médiathèque complète (doublons, tri intelligent, déplacement sécurisé).

## Construction

Workflow `.github/workflows/build-apk.yml` exécuté sur un push sur `main`. Le téléchargement de l'APK debug se fait depuis le ZIP Artifacts de la compilation réussie.
