# Wonder Apps 3.7 — Stabilisation et plan de validation

Ce document est un plan de tests, pas une certification de sécurité ni une preuve de fonctionnement du SSO Wonderbox.

## Périmètre contrôlé dans le code

- Décisions par site extraites dans `SiteRoutingPolicy`, avec tests JUnit : Atlassian/Microsoft/SSO, modes navigateur/WebView, hôtes autorisés et délai de verrouillage.
- L'injection des identifiants de sites simples n'est autorisée que sur l'hôte prévu et jamais vers les fournisseurs d'identité explicitement reconnus.
- La détection d'une option SSO passe avant la classification d'un formulaire en connexion automatique.
- L'analyse d'un nouveau site ne déclenche plus le nettoyage global de toutes les sessions WebView.
- Le PIN Wonder Apps est proposé en secours à la biométrie **par site**.
- Le délai de verrouillage est contrôlé avant de traiter un raccourci Android reçu alors que l'application est en arrière-plan.
- Nettoyage par site : seul un sous-ensemble des cookies et du stockage de l'origine WebView peut être nettoyé. Les sessions des navigateurs externes et le cache HTTP Basic global ne peuvent pas être supprimés de façon fiable par site.
- État de connexion : une page chargée et un code HTTP 2xx ne prouvent jamais l'authentification.
- Accueil avec peu d'applications : réduction des doublons « Récents » / « Favoris » / « Mes applications ».
- La compilation debug n'est distribuée que si `assembleDebug`, `lintDebug` et `testDebugUnitTest` retournent tous un code 0.

## Recette manuelle indispensable sur Samsung (même après tests unitaires verts)

1. Installer 3.7 **par-dessus** 3.6, ne pas désinstaller et confirmer la conservation de Plano/Jira, des logos, favoris, PIN et identifiants.
2. Plano : ouverture depuis la carte et depuis l'icône Samsung, test HTTP Basic puis formulaire AD, actualisation et bouton de déconnexion du site. Vérifier qu'aucun « mot de passe incorrect » n'apparaît sans message explicite du site.
3. Jira : lien de départ `https://wonderbox.atlassian.net/jira/servicedesk/projects/SUP/queues/custom/359` ; mode automatique/Edge ; SSO Microsoft selon politique Wonderbox ; retour à SUP. **Ne pas enregistrer id.atlassian.com comme point d'entrée.**
4. Choisir un site avec biométrie optionnelle : vérifier l'ouverture via empreinte, puis via PIN Wonder Apps, puis l'annulation sans ouverture.
5. Régler le verrouillage à 30 secondes : passer dans Edge, attendre plus de 30 secondes, toucher le raccourci Jira ou Plano ; vérifier que le verrouillage apparaît **avant** d'ouvrir le site.
6. Réinitialisation individuelle : confirmer que le message explique les limites, qu'aucune session Edge/Chrome n'est présentée comme effacée, et que Plano n'est pas déconnecté lorsque l'on nettoie un autre site.
7. Test d'accessibilité : Zoom/police système, thème sombre, appui long, logos, raccourcis, sauvegarde/export/import sur un autre appareil si disponible.
8. Test Formspark : la soumission apparaît dans Formspark ; contrôler séparément la livraison e-mail (dépend de l'antispam Wonderbox).

## Distribution et signature

La 3.7 est encore une APK **debug**, destinée au test sur le même appareil que les anciennes versions debug. La version release requiert une clé de signature privée créée et conservée par le propriétaire, hors du dépôt public. **Une APK release signée avec une autre clé que l'APK debug ne pourra pas être installée par-dessus celle-ci.** Avant une migration debug → release : exporter une sauvegarde chiffrée, vérifier le mot de passe de sauvegarde, puis installer la release et importer. L'application ne peut pas préserver les données Android lors d'une désinstallation par magie.

Le workflow release est manuel et doit échouer si la signature, lint ou les tests échouent. Aucune clé n'est incluse dans GitHub.
