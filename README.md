# Les Lapibreizh — La Médiathèque

Version **0.3.0** — sources Android pour compilation et essai sur le téléphone. **Ce ZIP n'est pas un APK précompilé et les nouvelles fonctions n'ont pas encore été testées sur l'appareil.**

## Fonctionnalités

- Conservé de la 0.2 : icône officielle, galerie MediaStore, photos et vidéos avec miniatures, albums, catégories, tri, sélection multiple, classement interne et partage.
- Recherche de média par nom ou album, filtre des captures d'écran, sélection en masse des éléments visibles ou de tous les résultats filtrés.
- Création, renommage et retrait de catégories : **ne modifie jamais les médias physiques**.
- Export/import du classement interne dans un fichier JSON choisi via Android. La sauvegarde ne contient pas les images, seulement les catégories et leurs associations à des identifiants de médias. Les identifiants doivent être encore valides lors de la restauration.
- Recherche asynchrone des doublons **strictement identiques octet pour octet**, parmi les photos jusqu'à 50 Mo, par taille et empreinte SHA-256. Aucun doublon effacé automatiquement. Ne repère pas les photos qui se ressemblent sans être des fichiers identiques.
- Copie de 1 à 250 médias dans un dossier choisi via le sélecteur Android. Les originaux restent là où ils sont. En cas d'erreur d'une copie, l'application essaie de retirer le fichier incomplet. Vérifier le dossier de destination après l'opération.
- Sur Android 11+ seulement, mise à la corbeille de 1 à 100 médias après confirmation dans l'application **et** dans la boîte de dialogue Android. Ce n'est ni un effacement immédiat garanti, ni un effacement sécurisé. Ne pas confondre classement interne et corbeille du téléphone.

## Attention aux mises à jour

La compilation GitHub Actions utilise une signature debug qui peut changer à chaque nouveau run. Android peut refuser une installation par-dessus la V0.2. Si tu désinstalles la V0.2, tu perds **ses classements internes**, notamment les 42 captures de test, car cette ancienne version ne sait pas encore exporter ses données. **Les photos originales restent intactes.** La V0.3 ajoute l'export/import JSON pour les installations suivantes, sous réserve que les identifiants de médias soient restés les mêmes. Une signature stable et privée est nécessaire pour permettre des mises à jour sans désinstallation ; elle n'est pas encore configurée.

## Construction

Décompresser le contenu du ZIP dans la **racine du dépôt Gitling** `Les-Lapibreizh-Android`, avec remplacement des fichiers de même nom, sans créer de dossier supplémentaire autour. Faire `Stage all`, puis `Commit`, puis `Push tout`. Vérifier que GitHub Actions passe au vert ; télécharger l'artifact `Les-Lapibreizh-APK` et installer `app-debug.apk`.

## Première vérification conseillée

Lancer l'application, ouvrir Mes images, vérifier les miniatures puis filtrer Captures. Tester d'abord une **copie de deux captures sans importance**, vérifier la présence des copies dans le dossier choisi, puis, séparément, tester la corbeille sur une capture que tu acceptes réellement de supprimer. Ne pas tester sur des photos importantes.
