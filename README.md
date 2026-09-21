# Les Lapibreizh — Médiathèque Android 0.4.1

Correctif de la version 0.4, conçu pour **mettre à jour l’application sans effacer ses données** lorsque la clé de signature est la même.

## Ce qui a changé

- Accueil reconstruit à partir de la maquette validée : couverture entière, deux cartes cliquables, actions reliées aux fonctions existantes et pied de page illustré. L’espace noir artificiel ne vient plus d’une barre de navigation séparée sur l’accueil.
- Navigation Images / Vidéos organisée en grilles de catégories illustrées (3 colonnes sur téléphones suffisamment larges). Nombres calculés depuis les médias accessibles, jamais depuis les exemples des maquettes.
- Galeries : taille des vignettes réglable, durée réelle des vidéos lorsqu’elle est fournie par Android, tri par durée pour les vidéos ; favoris, partage, copie, classement, sauvegarde JSON et corbeille de la V0.4 conservés.
- Les nouvelles catégories vidéo utilisées dans le classement sont enregistrées afin d’être comprises dans la sauvegarde JSON.
- `versionCode=5`, `versionName=0.4.1`.

## Signature : IMPORTANT

Le workflow livré dans `.github/workflows/build-apk.yml` remplace l’ANCIEN workflow debug. Il utilise les deux secrets GitHub `LAPIBREIZH_KEYSTORE_B64` et `LAPIBREIZH_KEYSTORE_PASSWORD`, produit `app-release.apk`, et vérifie sa signature avant d’autoriser le téléchargement.

AUCUNE clé privée, aucun mot de passe, aucune archive secrète n’est présent dans les sources. **Ne jamais mettre le kit privé dans Gitling ou sur GitHub.**

Une V0.4 installée depuis `app-debug.apk` peut avoir une autre signature : dans ce cas la V0.4.1 release ne pourra pas s’installer dessus. Ne pas désinstaller par réflexe : exporter le JSON, conserver l’APK/kit et confirmer les signatures avant toute transition.

## Non implémenté dans ce correctif

Les maquettes de l’import intelligent par compréhension des images, montage vidéo, suppression définitive et déplacement physique complet sont des références visuelles, pas des fonctions annoncées comme terminées. `Classer` affecte une catégorie interne, `Copier` crée une copie dans un dossier choisi sans toucher à l’original, `Corbeille` reste récupérable suivant Android ; SHA-256 ne repère que les doublons exacts.

La compilation Android et l’installation réelle nécessitent un passage réussi par GitHub Actions et un essai sur téléphone. Aucun APK testé n’est inclus dans ce ZIP.
