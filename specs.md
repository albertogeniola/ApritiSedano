# Documento Architetturale: Sistema Controllo Accessi BLE Offline (Garage -2)

## 1. Descrizione del Progetto e Vincoli Ambientali

L'obiettivo è realizzare un sistema per l'apertura sicura di una basculante motorizzata situata in un box auto al piano -2.

- **Vincoli di Rete:** Assenza totale di copertura Wi-Fi e rete cellulare (4G/5G) in modalità operativa ordinaria. Il sistema deve operare in modalità 100% offline e _local-first_.
- **Gestione del Tempo (Fase Iniziale):** In questa fase non è presente un modulo RTC hardware esterno. Il microcontrollore si affida al proprio orologio di sistema interno, che viene sincronizzato _on-demand_ tramite una procedura di boot-strap Wi-Fi/NTP attivabile manualmente via seriale.
- **Interfaccia Utente (Doppio Innesco):** \* _In auto:_ Comando vocale ("Okay Google, apri il box") intercettato dallo smartphone collegato al sistema di infotainment della vettura tramite Android Auto.
  - _A piedi:_ Lettura di un Tag NFC passivo incollato all'esterno del box tramite lo smartphone.
- **Infrastruttura Elettrica:** Alimentazione fissa a 220V disponibile (tramite trasformatore USB). Nessun vincolo di deep-sleep o risparmio energetico critico sul microcontrollore.
- **Attuazione:** Motore esistente pilotabile tramite chiusura di un contatto pulito (logica toggle/impulso).

---

## 2. Architettura di Rete: "Bidirectional Connectionless Communication"

Per superare i problemi di latenza e le cadute di connessione in movimento lungo la rampa, si utilizza un handshake asincrono tramite **BLE Advertising / Scanning simultaneo**.

1.  **Fase Faro (Smartphone):** Ricevuto l'intent vocale o letto il Tag NFC, lo smartphone genera un payload TOTP. L'app avvia la trasmissione di pacchetti BLE in broadcast (Advertising) a ripetizione e attiva contemporaneamente uno Scanner BLE in ascolto. Il burst ha un timeout di sicurezza di 60 secondi.
2.  **Fase Intercettazione (ESP32):** Il microcontrollore, in costante ascolto (Observer), intercetta un pacchetto e ne valida il TOTP basandosi sul tempo interno sincronizzato.
3.  **Fase Attuazione & Idempotenza:** Se il TOTP è nuovo, attiva il relè. Se il TOTP è un duplicato ravvisato nello stesso burst, ignora l'azione meccanica per evitare azionamenti ripetuti.
4.  **Fase Eco (ACK):** L'ESP32 legge lo stato fisico della basculante e inizia a trasmettere un pacchetto di risposta (Advertising ACK) per 3 secondi. Ogni pacchetto TOTP duplicato ricevuto dal telefono resetta il timer di questo ACK, prolungandone la trasmissione per contrastare il fading del segnale.
5.  **Fase Chiusura:** Lo Scanner dello smartphone capta l'ACK dell'ESP32, interrompe immediatamente il proprio Advertising, chiude la transazione e aggiorna l'interfaccia utente.

---

## 3. Distinta Base Hardware (BOM)

- **Microcontrollore:** Seeed Studio XIAO ESP32-C6 (ingombro minimo, Bluetooth 5.3, Wi-Fi 6 per la fase di sync).
- **Attuatore:** Modulo Relè a 1 canale (5V). Morsetti COM e NO collegati in parallelo al pulsante a muro esistente.
- **Sensore di Stato:** Contatto magnetico cablato (Reed Switch). Un polo a GND, l'altro a un pin GPIO configurato in `INPUT_PULLUP`.
- **Innesco Esterno:** Tag NFC Passivo (es. NTAG215) adesivo, programmato con un record NDEF testuale o un URI custom.
- **Alimentazione:** Caricatore USB da muro (220V -> 5V USB-C), collegato direttamente alla porta nativa dello XIAO.
- _(Nota: Il modulo RTC fisico DS3231 è rimosso da questa fase del progetto, ma lo slot nel case viene mantenuto per predisposizione futura)._

---

## 4. Specifiche per lo Sviluppo Software - Lato ESP32 (C++ / Arduino IDE)

Il firmware dovrà implementare le seguenti librerie e logiche:

### A. Logica Operativa BLE e Sicurezza

- **Sicurezza TOTP:** Validatore compatibile con RFC 6238, dotato di una finestra di tolleranza di $\pm 1$ o $2$ step temporali per compensare eventuali derive del clock interno dell'ESP32 o ritardi di guida.
- **Gestione BLE Async:** Uso nativo dell'SDK Espressif (`BLEDevice`, `BLEScan`, `BLEAdvertising`). Il chip opera come `Scanner` che si sospende temporaneamente solo per inviare l'ACK di risposta.

### B. Interfaccia di Configurazione Seriale (CLI On-Demand)

Il ciclo principale (`loop()`) deve monitorare costantemente la porta seriale a 115200 baud. Inviando la stringa `conf` seguita da `Invio` (`\n`), l'ESP32 deve sospendere temporaneamente le funzioni BLE ed entrare in un menu interattivo testuale.

Il menu deve prevedere le seguenti opzioni:

1.  **Time sync via NTP:**
    - Attiva il modulo Wi-Fi dell'ESP32.
    - Esegue una scansione delle reti disponibili (`WiFi.scanNetworks()`) e stampa l'elenco dei relativi SSID sulla console seriale.
    - Richiede all'utente la selezione dell'indice della rete e l'inserimento della chiave di sicurezza (PSK), se necessaria.
    - Effettua la connessione all'Access Point e interroga i server NTP standard (es. `pool.ntp.org`) per aggiornare l'orologio interno del chip (`configTime`).
    - Una volta completata con successo la sincronizzazione, disabilita completamente il modulo Wi-Fi e ripristina lo stato e la configurazione radio BLE originale.
2.  **Set PassPhrase/Secret:**
    - Richiede l'inserimento del segreto condiviso per l'algoritmo TOTP.
    - La stringa deve essere inserita dall'utente codificata in **Base64**.
    - Il firmware dell'ESP32 deve decodificare internamente la stringa Base64 per estrarre i byte del segreto originale.
    - Il segreto deve essere salvato in modo permanente nella memoria non volatile del chip (utilizzando la libreria `Preferences.h` su NVS) per garantirne la persistenza a seguito di riavvii o interruzioni di corrente.

---

## 5. Specifiche per lo Sviluppo Software - Lato Android (Java)

L'app fungerà da vettore logico e dovrà essere interamente sviluppata in **Java**.

- **Gestione NFC (A piedi):** Implementazione di `NfcAdapter` e `ForegroundDispatch` nell'Activity principale. L'app cattura l'Intent `ACTION_NDEF_DISCOVERED` del Tag NTAG215, innescando istantaneamente la generazione del TOTP e il burst BLE.
- **App Actions / Voice (In Auto):** Integrazione di `shortcuts.xml` per mappare un Built-In Intent (`actions.intent.OPEN_APP_FEATURE`) al comando vocale "Apri il box".
- **Servizi in Background:** Entrambi i trigger indirizzano l'esecuzione verso un `ForegroundService` o un `BroadcastReceiver` in Java, garantendo l'esecuzione _headless_ senza interrompere la navigazione su Android Auto o richiedere lo sblocco manuale dello schermo.
- **BLE Manager (Java):** Utilizzo delle classi `BluetoothLeAdvertiser` (timeout nativo a 60.000 ms) e `BluetoothLeScanner` (con `ScanFilter` basato su UUID/MAC dell'ESP32). La logica asincrona viene gestita tramite `AdvertiseCallback` e `ScanCallback`.
- **Permessi:** Richiesta esplicita nel Manifest per `BLUETOOTH_ADVERTISE`, `BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT`, `NFC` e `ACCESS_FINE_LOCATION`.

---

## 6. Design Industriale - Case Stampato in 3D (OpenSCAD)

Contenitore progettato tramite script parametrico per garantire l'isolamento dei cavi e un montaggio a muro robusto. Ottimizzato per la stampa in PETG.

```openscad
/* Case Parametrico per ESP32-C6, Relè
   Fori separati per potenza e segnale.
*/
int_L = 100; int_W = 75; int_H = 28; wall = 2.5; post_D = 6;
screw_D = 3; mount_D = 4.5; relay_hole_D = 6; sensor_hole_D = 5;
$fn = 50;

xiao_L = 21.5; xiao_W = 18; xiao_Z = 3; usb_W = 10; usb_H = 5;
relay_hole_X = 38; relay_hole_Y = 21; relay_post_H = 4;

module box_base() {
    difference() {
        cube([int_L + (wall*2), int_W + (wall*2), int_H + wall]);
        translate([wall, wall, wall]) cube([int_L, int_W, int_H + 1]);
        translate([(int_L/2) + 20 + wall, int_W + (wall*2), wall + (int_H/2)])
            rotate([90, 0, 0]) cylinder(h=wall+2, d=relay_hole_D, center=true);
        translate([(int_L/2) - 20 + wall, int_W + (wall*2), wall + (int_H/2)])
            rotate([90, 0, 0]) cylinder(h=wall+2, d=sensor_hole_D, center=true);
        translate([-1, wall + 10 + (xiao_W/2) - (usb_W/2), wall + xiao_Z + 1])
            cube([wall + 2, usb_W, usb_H]);
    }
    translate([wall, wall + 10, wall]) {
        cube([xiao_L, xiao_W, xiao_Z]);
        translate([xiao_L-2, xiao_W, xiao_Z]) cube([2, 1.5, 3]);
        translate([xiao_L, xiao_W-2, xiao_Z]) cube([1.5, 2, 3]);
        translate([xiao_L-2, -1.5, xiao_Z]) cube([2, 1.5, 3]);
        translate([xiao_L, 0, xiao_Z]) cube([1.5, 2, 3]);
    }
    translate([wall + 50, wall + 10, wall]) {
        standoff(relay_post_H);
        translate([relay_hole_X, 0, 0]) standoff(relay_post_H);
        translate([0, relay_hole_Y, 0]) standoff(relay_post_H);
        translate([relay_hole_X, relay_hole_Y, 0]) standoff(relay_post_H);
    }
    translate([wall + (post_D/2), wall + (post_D/2), wall]) screw_post();
    translate([int_L + wall - (post_D/2), wall + (post_D/2), wall]) screw_post();
    translate([wall + (post_D/2), int_W + wall - (post_D/2), wall]) screw_post();
    translate([int_L + wall - (post_D/2), int_W + wall - (post_D/2), wall]) screw_post();
    translate([-15, (int_W/2) + wall - 10, 0]) wall_mount_tab();
    translate([int_L + (wall*2), (int_W/2) + wall - 10, 0]) wall_mount_tab();
}

module standoff(altezza) {
    difference() { cylinder(h=altezza, d=5); translate([0, 0, -1]) cylinder(h=altezza+2, d=2); }
}
module screw_post() {
    difference() { cylinder(h=int_H, d=post_D); translate([0, 0, 2]) cylinder(h=int_H, d=screw_D); }
}
module wall_mount_tab() {
    difference() { cube([15, 20, wall]); translate([7.5, 10, -1]) cylinder(h=wall+2, d=mount_D); }
}
module box_lid() {
    difference() {
        cube([int_L + (wall*2), int_W + (wall*2), wall]);
        translate([wall + (post_D/2), wall + (post_D/2), -1]) cylinder(h=wall+2, d=screw_D+0.5);
        translate([int_L + wall - (post_D/2), wall + (post_D/2), -1]) cylinder(h=wall+2, d=screw_D+0.5);
        translate([wall + (post_D/2), int_W + wall - (post_D/2), -1]) cylinder(h=wall+2, d=screw_D+0.5);
        translate([int_L + wall - (post_D/2), int_W + wall - (post_D/2), -1]) cylinder(h=wall+2, d=screw_D+0.5);
    }
}

box_base();
translate([0, int_W + 30, 0]) box_lid();s
```
