# Loop Mod (Fabric 1.21.11)

Touches (modifiables dans Options > Contrôles) :
- R : enregistrer / arrêter l'enregistrement
- G : lancer / arrêter la boucle (chrono + blocs cassés)
- H : lancer / arrêter la pêche auto (chrono + pêches réussies)

## Compiler
1. Installe le JDK 21.
2. Ouvre le dossier dans IntelliJ IDEA (import Gradle), ou génère le wrapper :
   gradle wrapper   (Gradle 9.x requis par Loom 1.14)
3. ./gradlew build   (Windows : gradlew.bat build)
4. Le .jar est dans build/libs/ (prends celui sans "-sources").
5. Mets-le dans .minecraft/mods avec Fabric Loader et Fabric API.

Tester sans installer : ./gradlew runClient
