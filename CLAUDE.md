# Seafile Big Upload (app Android per foto e video)

App Android non ufficiale che carica da sola foto e video del telefono su un server Seafile, **a pezzi**, così da aggirare il limite di dimensione per richiesta
di servizi come Cloudflare (100 MB sul piano gratuito). Ideatore: William Baglioni. Repository: https://github.com/William-webext/seafile-big-upload
I commenti nel codice e questo file sono in italiano; il README è in inglese. I testi dell'app sono in inglese (base) e italiano.
Dettagli privati (percorsi, dispositivi, server) e storia del lavoro: `NOTE-PRIVATE.md`, file locale **ignorato da git**: non committarlo e non copiarne il contenuto qui.

## Perché esiste

Cloudflare Free e Pro rifiutano con 413 le richieste oltre ~100 MB (Business 200 MB). L'app Android ufficiale (`haiwen/seadroid`, 4.0.14) manda in una sola richiesta i file fino a 200 MiB
(`CHUNK_TRIGGER_SIZE_BYTES` in `ParentEventUploader.java`, pezzi da 8 MiB solo sopra): i video tra ~105 e 209 MB falliscono e l'app li riprova in continuazione.
Segnalazione upstream: https://github.com/haiwen/seadroid/issues/1208. Se un giorno l'app ufficiale risolve, valutare se questa serve ancora.

## Cosa fa

1. Legge foto e/o video (scelta dell'utente, predefinito solo video) degli album scelti da MediaStore. Salta i file modificati da meno di 90 secondi.
2. **Registro locale** (`Db.kt`, SQLite `video.db`, schema 2): chiave `nome|dimensione`; stati `da_caricare`, `fatto_server` (era già sul server al confronto iniziale), `fatto` (caricato dall'app e controllato),
   `assente` (non più sul telefono). Dopo un errore il tentativo è rimandato (15 min, 1 h, 4 h, 16 h, poi ogni giorno; un rifiuto definitivo 4xx aspetta 24 h). Non rilegge tutto ogni volta.
3. **Confronto una tantum con il server** (`Caricatore.riconcilia`): elenco ricorsivo della cartella scelta e confronto per nome+dimensione, così i file già presenti (anche in sottocartelle `AAAA/MM`) non si ricaricano.
   Si rifà solo con il pulsante o se cambiano libreria, cartella, album o tipo di file.
4. Prima di caricare chiede al server se `cartella/nome` esiste (stessa dimensione → fatto; dimensione diversa → **non sovrascrive mai**, errore visibile).
5. Carica con `upload-link` + POST multipart; sopra 8 MiB a pezzi da 8 MiB con `Content-Range` e `Content-Disposition`; 3 tentativi per pezzo; `replace=0`. Manda `last_modify` (RFC 3339, UTC)
   con la data di modifica originale: il fileserver di Seafile la conserva (verificato, anche con i pezzi).
6. **Controllo finale**: chiede al server dimensione e data e solo allora segna `fatto` (lo stato di un client non basta mai a provare che un file sia arrivato).
7. Sottocartelle a scelta (`Prefs.raggruppa`): nessuna, per anno (`AAAA`), per anno e mese (`AAAA/MM`); la data è quella scritta nel nome (`AAAAMMGG…`), altrimenti di scatto/modifica. Le cartelle mancanti si creano
   un livello alla volta (`GET …/dir/?t=d`, poi `POST …/dir/` con `operation=mkdir`).
8. Non cancella mai niente dal telefono né dal server.

## Quando gira (`Pianifica.kt`, `VideoWorker.kt`, WorkManager)

- **Controllo periodico (la rete di sicurezza)**: ogni 15 min / 1 h (predefinito) / 6 h / 1 giorno / 1 settimana, a scelta. Solo con la rete scelta (predefinito solo Wi-Fi) e batteria non scarica.
  Un giro senza niente da fare non fa richieste di rete e non mostra notifiche: prima `Caricatore.piano()` (solo MediaStore e registro), poi, solo se serve, servizio in primo piano e notifica.
- **Trigger sui file nuovi** (content URI di MediaStore, attesa 2 minuti, massimo 10): accelera, **ma non è affidabile** (WorkManager lo riprogramma e le modifiche già avvenute si perdono): non va mai usato da solo.
- Se a fine giro resta qualcosa che diventerà caricabile più tardi, l'app programma da sola un controllo a quell'ora (`Pianifica.riprovaTra`).
- "Carica ora" avvia un giro subito; mentre il lavoro è in coda o in corso il pulsante sparisce e compare "Interrompi". Un solo giro alla volta (lucchetto in `Caricatore`).
- Il nome della classe `VideoWorker` resta così perché WorkManager lo ha salvato nei lavori programmati.

## Accesso e sicurezza

- Accesso con utente, password e codice a due fattori **dentro l'app**; la password non viene salvata. Si ottiene un **token per dispositivo** (`platform=android`, `device_id`, `device_name`):
  compare tra i dispositivi collegati di Seafile e si revoca da lì. **`device_id` per Android: da 1 a 16 caratteri esadecimali** (con un valore sbagliato il login dà `400 invalid device id` dopo aver accettato password e codice).
- Il token è cifrato con una chiave del Keystore Android (`Cripto.kt`). `allowBackup=false`. Solo HTTPS.
- I server Seafile possono limitare i login falliti (`LOGIN_ATTEMPT_LIMIT`): dopo qualche errore chiedono il captcha e bloccano anche le altre app. Il pulsante di accesso si spegne per un minuto dopo un errore.
- **Mai nel repository:** `key.properties`, `*.jks`, `*.keystore`, `local.properties`, `backup/`, `NOTE-PRIVATE.md`, indirizzi/token/password/identificativi di un server vero. Vedi `.gitignore`.

## Struttura

`app/src/main/java/dev/seafilebigupload/`: `MainActivity.kt` (schermata unica, costruita da codice), `ElencoActivity.kt` (elenco dei file e stato), `InfoActivity.kt`, `Ui.kt` (righe stile impostazioni),
`Prefs.kt`, `Cripto.kt`, `Db.kt`, `Media.kt` (lettura MediaStore, data per le sottocartelle), `SeafileClient.kt` (API di Seafile), `Caricatore.kt` (il giro), `VideoWorker.kt`, `Pianifica.kt`, `Lingua.kt` (`App`, `Lingua`, `T`).
Dipendenze: AndroidX core e appcompat, Material Components 1.12, WorkManager, OkHttp 4.12. minSdk 26, targetSdk 35, compileSdk 36. Kotlin 2.2.20, AGP 8.13.0, Gradle 8.14.3, Java 17.

## Regole di lavoro

- **Prove automatiche:** `./gradlew testDebugUnitTest` (JUnit, solo logica pura in `app/src/test/`: data nei nomi dei file e nomi delle cartelle, anche con lingue che usano altre cifre). Aggiungerne quando si tocca quella logica.
- **Cifre e formati:** ogni `format(...)` che produce nomi di cartelle o percorsi usa `Locale.ROOT` (con il telefono in arabo, persiano, bengalese, marathi `String.format` scrive cifre non latine).
- **Un solo giro alla volta:** un lavoro che parte mentre un altro sta caricando (`Caricatore.occupato()`) esce subito e non tocca avanzamento né notifica; il trigger si riarma solo se il caricamento automatico è ancora acceso.
- **Indirizzo del server:** solo `https://` (l'app aggiunge lo schema se manca e rifiuta `http://`; il link di caricamento che arriva dal server deve essere https). Scollegarsi chiede al server di revocare il token (`/api2/logout-device/`);
  un token revocato dal web risponde con `X-Seafile-Wiped` e vale come "accesso non valido".
- **Lingua dell'app:** all'avvio si applica solo se scelta in modo esplicito (non "automatica"), per non cancellare la scelta fatta dalle impostazioni di Android.

- **Testi:** inglese di base in `res/values/strings.xml`, italiano in `res/values-it/strings.xml`, **stesse chiavi**. Mai testi scritti nel codice: nelle schermate `getString(R.string.x)`, fuori dalle schermate `T.s(R.string.x, …)`.
  Lingua dell'app: automatica (quella del sistema: inglese salvo telefono in italiano), English, Italiano (`Lingua.applica` con `AppCompatDelegate.setApplicationLocales`).
- **Trappola dei dialoghi:** in un `AlertDialog` con `setMessage()` **e** un elenco di scelte Android mostra solo il messaggio e l'elenco sparisce. Nei dialoghi con elenco niente `setMessage`.
- **Permessi:** servono `READ_MEDIA_VIDEO` e/o `READ_MEDIA_IMAGES` secondo il tipo scelto; vanno richiesti anche dopo aver ripreso un registro/impostazioni che cambiano il tipo.
- **Registro:** si può salvare in un file JSON (con le impostazioni, non l'accesso) e riprendere (Avanzate): serve quando si reinstalla l'app con un'altra chiave di firma o si cambia telefono.
  Le versioni di rilascio non sono "debuggable": `adb run-as` non funziona, per diagnosticare si usa il logcat (tag `BigUpload`) e il salvataggio del registro.

## Compilare, firmare, installare (Windows)

- JDK: quello di Android Studio (`JAVA_HOME` = cartella `jbr`). `./gradlew assembleDebug` (chiave di prova) o `./gradlew assembleRelease` (firmata). `local.properties` con `sdk.dir=…` deve esistere (ignorato da git).
  Gradle ha bisogno di collegamenti locali: in alcuni ambienti protetti fallisce con `Unable to establish loopback connection`; va lanciato da un terminale normale.
- **Firma di rilascio:** crea un keystore **fuori dal repository** (`keytool -genkeypair -keystore … -alias … -keyalg RSA -keysize 4096 -validity 10000`) e un file `key.properties` nella radice con
  `storeFile`, `storePassword`, `keyAlias`, `keyPassword`. `app/build.gradle.kts` lo legge; senza di esso `assembleRelease` si ferma. **Fai una copia sicura del keystore e della password:** senza, non puoi
  pubblicare aggiornamenti che si installino sopra l'app e chi l'ha installata deve disinstallare e rifare l'accesso. La versione di prova e quella di rilascio hanno firme diverse (non si aggiornano a vicenda).
- Installare: `adb install -r app-release.apk` (stessa chiave: aggiorna senza perdere i dati), oppure copiare l'APK sul telefono e installarlo da «origini sconosciute».
- Verificare la firma: `apksigner verify --print-certs app-release.apk`.
- Diagnosi sul telefono: `adb logcat -d | grep -E "BigUpload|WM-"`; `adb shell dumpsys jobscheduler` (job dell'app, vincoli soddisfatti, bucket di standby); `adb shell cmd jobscheduler run -f -n androidx.work.systemjobscheduler <pacchetto> <id>`
  (un job periodico forzato prima del tempo viene rimandato da WorkManager: non prova niente).

## Cose da sapere

- Il fileserver di Seafile legge il campo `last_modify` in caricamento (anche a pezzi: vale quello dell'ultimo); l'app ufficiale lo manda, per questo le foto già caricate hanno la data giusta.
- Per Seafile un caricamento a pezzi ripreso dopo un'interruzione riparte dal primo pezzo (la ripresa con `uploadedBytes` non è implementata); il link di caricamento scade dopo circa un'ora.
- Il Play Store non è previsto: si distribuisce l'APK. Il nome «Seafile» è un marchio altrui: l'app dichiara di essere non ufficiale (schermata Informazioni).
- Sui telefoni Samsung/Xiaomi servono «nessuna restrizione» di batteria e, su Xiaomi, avvio automatico e blocco nelle recenti: la riga «Batteria» dell'app apre la richiesta di sistema.
