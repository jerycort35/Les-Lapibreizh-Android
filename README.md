# Les Lapibreizh — Médiathèque Android 0.4

Version Android Kotlin, code source pour le projet de Jérémy. Depuis la V0.3
fonctionnelle : évolution visuelle basée sur les maquettes validées, catégories,
favoris, tri explicite et signature privée de release.

**Lire `GUIDE_INSTALLATION_V04.txt` avant de pousser ou désinstaller quoi que ce soit.**
Le ZIP des sources ne contient aucune clé ni mot de passe. Le kit de signature est
une archive séparée et personnelle, à NE PAS publier sur GitHub.

L'APK est généré par GitHub Actions après création des secrets :
`LAPIBREIZH_KEYSTORE_B64` et `LAPIBREIZH_KEYSTORE_PASSWORD`.

Attention : transition V0.3 → V0.4 exige une seule désinstallation car V0.3 avait
une signature debug éphémère ; export JSON AVANT, import JSON APRÈS.

La V0.4 ne promet PAS de tri automatique d'images par IA ni de suppression sécurisée.
