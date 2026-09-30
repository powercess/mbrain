<div align="center">

<img src="assets/brand/mbrain-logo.svg" width="88" height="88" alt="Logo MBrain" />

# MBrain

**Une passerelle MCP Android pour les agents IA.**

Informations sur l’appareil · Gestion des applications · Fichiers · Root / Shizuku · Agrégation MCP

<p>
  <img src="https://img.shields.io/badge/Android-9.0%2B-3DDC84?style=flat-square&amp;logo=android&amp;logoColor=white" alt="Android 9.0 ou version ultérieure" />
  <img src="https://img.shields.io/badge/MCP-HTTP-5E6AD2?style=flat-square" alt="MCP HTTP" />
  <a href="https://github.com/powercess/mbrain/releases/latest"><img src="https://img.shields.io/github/v/release/powercess/mbrain?style=flat-square&amp;color=14756A" alt="Dernière version" /></a>
</p>

[简体中文](README.zh_CN.md) | [繁體中文](README.zh_TW.md) | [English](README.md) | **Français** | [日本語](README.ja.md)

[Démarrage rapide](#démarrage-rapide) · [Connexion d’un agent](#connecter-un-agent-ia) · [Services MCP](#ajouter-des-services-mcp) · [FAQ](#faq) · [Signaler un problème](https://github.com/powercess/mbrain/issues)

</div>

---

MBrain est une passerelle Android pour le Model Context Protocol (MCP). Elle permet aux agents IA de consulter l’état du téléphone, de gérer les applications, de manipuler les fichiers et d’exécuter des commandes. Elle regroupe aussi des services MCP supplémentaires derrière un point d’accès unique. Choisissez les capacités à activer sur votre téléphone et arrêtez la passerelle à tout moment.

## Aperçu

<table>
  <tr><th>Accueil</th><th>Capacités</th><th>Paramètres</th></tr>
  <tr>
    <td><img src="assets/screenshots/home.png" width="240" alt="Accueil : outils, services et commande de la passerelle" /></td>
    <td><img src="assets/screenshots/capabilities.png" width="240" alt="Gestion des capacités Root, Shizuku et applications" /></td>
    <td><img src="assets/screenshots/settings.png" width="240" alt="Paramètres de connexion, apparence et historique" /></td>
  </tr>
</table>

<sub>Captures de l’application. Les outils disponibles dépendent des capacités activées et des services connectés. Thèmes clair, sombre et système disponibles.</sub>

## Fonctionnalités

| Capacité | Utilisation |
| --- | --- |
| **Informations sur l’appareil** | Consulter les caractéristiques, la batterie, le stockage et le réseau |
| **Gestion des applications** | Lister, examiner et lancer les applications ; les accès privilégiés permettent aussi leur installation, désinstallation et arrêt |
| **Commandes et fichiers** | Exécuter des commandes via Root ou Shizuku ; parcourir, lire, écrire, copier et déplacer des fichiers |
| **Agrégation MCP** | Connecter des services HTTP MCP locaux ou gérer des processus stdio MCP |
| **Point d’accès unique** | Rechercher les outils, consulter leur description, copier les paramètres de connexion et voir l’historique récent |
| **Accès distant** | Utiliser le client frpc intégré pour exposer MBrain ou d’autres services du téléphone via des tunnels TCP |

- **Activation à la demande :** Root et Shizuku disposent de commutateurs et de vérifications d’autorisation séparés.
- **Contrôle immédiat :** démarrez ou arrêtez la passerelle depuis l’accueil ou arrêtez-la depuis la notification persistante.
- **Services personnalisés :** renseignez vos propres noms et configurations, sans dépendre d’une application tierce particulière.

## Démarrage rapide

### 1. Installer MBrain

Nécessite **Android 9.0 ou une version ultérieure**.

Téléchargez `mbrain-v<version>-universal.apk` depuis [GitHub Releases](https://github.com/powercess/mbrain/releases/latest) et installez-le sur votre appareil Android. L’APK universel prend en charge ARM64, ARM32, x86_64 et x86.

Chaque publication comprend des notes de version et un fichier de sommes de contrôle `SHA256SUMS.txt`. Pour compiler l’application et utiliser les variantes de développement, consultez le [guide de développement](docs/development.md) (chinois simplifié).

### 2. Choisir les capacités

Ouvrez l’onglet **Capacités** :

- Les informations sur l’appareil ne nécessitent pas Root.
- Activez la gestion des applications selon vos besoins.
- Pour les opérations privilégiées, activez **Root** ou **Shizuku** et accordez les autorisations demandées.

Root nécessite un appareil rooté et une autorisation accordée à **MBrain lui-même**. Pour Shizuku, installez et démarrez [Shizuku](https://shizuku.rikka.app/). Les deux accès peuvent être activés séparément.

### 3. Démarrer la passerelle

Depuis **Accueil**, appuyez sur le bouton de démarrage en bas à droite. Une fois la passerelle en cours d’exécution, ouvrez les **coordonnées et identifiants de connexion** pour copier l’URL MCP et le jeton Bearer.

## Connecter un agent IA

Ajoutez un service dans un client prenant en charge **MCP sur HTTP et l’authentification Bearer** :

| Paramètre | Valeur |
| --- | --- |
| Nom | `MBrain`, ou un nom personnalisé |
| URL MCP | `http://127.0.0.1:8765/mcp` |
| Authentification | Jeton Bearer |
| Jeton | À copier depuis la page des identifiants dans MBrain |

Pour une configuration par en-tête HTTP :

```http
Authorization: Bearer <votre-jeton>
```

Les **clients sur le même téléphone** utilisent directement cette adresse. Les **clients sur ordinateur** nécessitent une connexion ADB et une redirection de port :

```bash
adb forward tcp:8765 tcp:8765
```

Si le port par défaut est occupé, MBrain en choisit un disponible. Utilisez l’adresse et la commande ADB affichées dans l’application. Le jeton est chiffré sur l’appareil, conservé après redémarrage et réinitialisable depuis la page des identifiants.

Commencez par une requête en lecture seule :

> Vérifie le niveau de batterie et l’espace de stockage disponible sur ce téléphone.

## Ajouter des services MCP

Ouvrez **MCP → Ajouter un service**, puis choisissez :

| Type | Usage | Configuration |
| --- | --- | --- |
| **Service HTTP** | Une autre application ou un processus exécute déjà le service sur le téléphone | Nom, URL locale, jeton facultatif |
| **Processus géré** | MBrain démarre et gère un service stdio MCP | Nom, exécutable, arguments séparés, identité d’exécution |

Enregistrez, puis sélectionnez **Connecter le service** dans ses détails. Ses outils rejoignent le catalogue et deviennent accessibles via la même URL MCP.

Les services HTTP doivent actuellement utiliser une adresse de bouclage. Préparez les exécutables et les environnements nécessaires sur l’appareil avant d’ajouter un processus géré : MBrain n’inclut ni Node.js ni Python.

## Accès distant

Le client **frpc** intégré peut rediriger MBrain ou d’autres services du téléphone via un serveur **frps** que vous hébergez. Dans **Paramètres → Accès distant → Configurer le serveur**, saisissez l’adresse, le port et le jeton, puis ajoutez des tunnels TCP.

Tous les tunnels partagent une configuration de serveur. Chacun dispose de commandes indépendantes de démarrage, d’arrêt et de nouvelle tentative. Les tunnels MCP suivent le cycle de vie de la passerelle ; les tunnels personnalisés fonctionnent indépendamment. Seule la redirection TCP de base est prise en charge. Consultez le [guide d’accès distant](docs/remote-access.md) (chinois simplifié).

## Autorisations et sécurité

- La passerelle écoute uniquement sur l’interface de bouclage du téléphone. Utilisez ADB depuis un ordinateur et les tunnels configurés pour l’accès distant.
- Tout client disposant du jeton peut appeler **tous les outils activés**. Les autorisations par client ne sont pas encore prises en charge.
- Les outils Root et Shizuku peuvent modifier réellement l’appareil. Connectez uniquement des clients de confiance et gardez le jeton privé.
- Choisissez les capacités sur le téléphone et arrêtez la passerelle lorsque l’accès n’est plus nécessaire.

## FAQ

<details>
<summary><strong>Puis-je utiliser MBrain sans Root ?</strong></summary>

Oui. Les informations sur l’appareil, la consultation et le lancement ordinaires d’applications ainsi que les services HTTP MCP locaux ne nécessitent pas Root. Shizuku offre un autre accès privilégié, dont les possibilités dépendent de son mode de démarrage.

</details>

<details>
<summary><strong>Pourquoi mon client ne se connecte-t-il pas ?</strong></summary>

Vérifiez que la passerelle fonctionne et que le jeton correspond à celui affiché dans l’application. Sur ordinateur, vérifiez la redirection ADB du port réel. La passerelle écoute uniquement sur l’interface de bouclage ; l’accès distant nécessite un tunnel TCP configuré.

</details>

<details>
<summary><strong>Pourquoi un outil est-il absent ?</strong></summary>

La liste dépend des capacités activées, des autorisations et des services connectés. Vérifiez les onglets Capacités et MCP, puis actualisez les outils dans votre client.

</details>

<details>
<summary><strong>Que se passe-t-il lorsque j’arrête la passerelle ?</strong></summary>

MBrain n’accepte plus de requêtes, déconnecte les services MCP externes et termine les processus enfants directement gérés. La passerelle ne démarre pas automatiquement au démarrage du téléphone. L’historique reste uniquement en mémoire.

</details>

## Contribuer

Les signalements, suggestions, améliorations de documentation et contributions de code sont les bienvenus. Utilisez les [modèles de tickets](https://github.com/powercess/mbrain/issues/new/choose) en précisant la version et les étapes de reproduction. Masquez les jetons et les informations privées dans les captures et journaux.

Le [guide de contribution](CONTRIBUTING.md) (anglais) décrit l’installation, la compilation, les vérifications et les pull requests. Créez une branche dédiée à partir du dernier `dev` et ciblez `dev` pour votre pull request. Le [guide de développement](docs/development.md) (chinois simplifié) détaille les tests sur appareil.

## Remerciements

- [droid-mcp](https://github.com/stixez/droid-mcp) : base Android MCP utilisée par MBrain, avec conservation de sa [licence Apache-2.0](vendor/droid-mcp/LICENSE) et de son [historique de provenance](vendor/droid-mcp/UPSTREAM.md).
- [Shizuku](https://github.com/RikkaApps/Shizuku) et [libsu](https://github.com/topjohnwu/libsu) : accès privilégiés Android.
- [RikkaHub](https://github.com/rikkahub/rikkahub) : référence pour les listes de paramètres et les interactions.

## Star History

Si MBrain vous est utile, vous pouvez soutenir le projet avec une étoile.

<picture>
  <source media="(prefers-color-scheme: dark)" srcset="https://api.star-history.com/svg?repos=powercess/mbrain&amp;type=Date&amp;theme=dark" />
  <source media="(prefers-color-scheme: light)" srcset="https://api.star-history.com/svg?repos=powercess/mbrain&amp;type=Date" />
  <img alt="Évolution des étoiles GitHub de MBrain" src="https://api.star-history.com/svg?repos=powercess/mbrain&amp;type=Date" />
</picture>

<sub>Graphique fourni par <a href="https://www.star-history.com/#powercess/mbrain&amp;Date">Star History</a>.</sub>
