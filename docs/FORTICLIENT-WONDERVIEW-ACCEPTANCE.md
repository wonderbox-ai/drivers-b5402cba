# Wonder Apps 3.9 — Recette FortiClient / WonderView

Objectif : conserver **Nova sans VPN** ; demander le VPN uniquement pour les sites configurés comme tels, notamment WonderView.

## Comportement attendu

- Nova : aucun lancement FortiClient par défaut. Conserver son éventuelle connexion automatique HTTP Basic → formulaire AD ; aucun changement de ses identifiants.
- WonderView : site interne `*.wonderbox.vpn` en HTTP, ouvert uniquement dans le navigateur, sans mot de passe enregistré ni injecté par Wonder Apps.
- WonderView avec VPN détecté : ouvrir le navigateur directement.
- WonderView sans VPN détecté : afficher « Ouvrir FortiClient VPN » ; l'utilisateur se connecte **dans FortiClient**, puis saisit le code temporaire FortiToken Mobile à six chiffres. Wonder Apps ne voit, ne conserve et ne transmet jamais ce code.
- Retour de FortiClient : si le VPN Android est détecté et que « Reprendre l’ouverture » est activé sur WonderView, ouvrir WonderView une fois. Après dix minutes, abandonner la reprise en attente ; ne pas ouvrir un ancien site à l'improviste.
- Sur le réseau interne sans VPN : permettre le choix explicite « Déjà sur le réseau interne ». Un VPN détecté n'atteste pas forcément de la route WonderView dans les configurations split tunnel.
- Si l'utilisateur ouvre une autre application dans Wonder Apps pendant l'attente, annuler l'ouverture WonderView en attente.
- Si Wonder Apps se verrouille pendant FortiClient, vérifier son propre code PIN / biométrie avant de reprendre. Ne jamais essayer de saisir le code FortiToken à la place de l'utilisateur.
- Si FortiClient VPN n'est pas installé/disponible : afficher une information et le lien Play officiel ; ne pas promettre la connexion automatique au VPN.

## Limites à ne pas masquer

FortiClient VPN gratuit n'expose pas ici de contrat d'intégration permettant à Wonder Apps de sélectionner un profil, renseigner les identifiants ou valider FortiToken ; seule l'ouverture de l'application est effectuée.
Une connexion VPN active n'est pas une preuve de disponibilité du site. Wonder Apps ne teste pas silencieusement en HTTP non chiffré et n'efface pas les sessions du navigateur externe. Pas de privilège administrateur demandé.
