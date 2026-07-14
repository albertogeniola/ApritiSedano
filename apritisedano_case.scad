/* 
 * ApritiSedano Smart Garage Case
 * 
 * Istruzioni per l'esportazione:
 * 1. Scarica e apri questo file con OpenSCAD (gratuito)
 * 2. Modifica la variabile 'part' qui sotto per scegliere cosa renderizzare.
 * 3. Premi F6 per renderizzare e poi clicca sull'icona STL per esportare.
 */

// Selettore parte da renderizzare
// [assembly = vista d'insieme, box = scatola, lid = coperchio, screws = le 4 viti]
part = "assembly"; 

// --- PARAMETRI DIMENSIONALI ---
width = 110;
length = 80;
height = 35;
wall = 3;

$fn = 60; // Risoluzione dei cilindri

// --- GENERATORI DI FILETTATURA 3D ---
// Usiamo una profilazione a corda ritorta per garantire la massima stampabilità 
// senza necessitare di librerie esterne o supporti. Passo della vite: 10mm
pitch = 10;

module coarse_screw() {
    // Testa della vite (Esagonale per presa facile anche a mano)
    cylinder(h=4, r=8, $fn=6);
    // Gambo filettato (lunghezza 15mm)
    translate([0,0,4])
    linear_extrude(height=15, twist=-360*(15/pitch), slices=100)
    translate([1.5, 0])
    circle(r=3.2, $fn=16);
}

module coarse_hole(h) {
    // Stesso profilo ma con tolleranza (raggio maggiore) per scorrere dolcemente
    translate([0,0,-1])
    linear_extrude(height=h+2, twist=-360*((h+2)/pitch), slices=100)
    translate([1.5, 0])
    circle(r=3.7, $fn=16);
}

// --- COMPONENTI ---

module box() {
    difference() {
        // Corpo principale esterno
        union() {
            cube([width + wall*2, length + wall*2, height + wall]);
            
            // Alette laterali per montaggio a muro (tasselli)
            translate([-15, length/2 - 10 + wall, 0]) 
                cube([15, 20, wall]);
            translate([width + wall*2, length/2 - 10 + wall, 0]) 
                cube([15, 20, wall]);
                
            // Supporti rinforzati per le viti (4 angoli)
            translate([wall*2.5, wall*2.5, 0]) cylinder(h=height+wall, r=7);
            translate([width - wall*0.5, wall*2.5, 0]) cylinder(h=height+wall, r=7);
            translate([wall*2.5, length - wall*0.5, 0]) cylinder(h=height+wall, r=7);
            translate([width - wall*0.5, length - wall*0.5, 0]) cylinder(h=height+wall, r=7);
        }
        
        // Cavo interno (spazio vuoto per l'elettronica)
        translate([wall, wall, wall]) 
        cube([width, length, height + wall + 1]);
        
        // Fori svasati per tasselli a muro (diametro 5mm)
        translate([-7.5, length/2 + wall, -1]) cylinder(h=wall+2, r=2.5);
        translate([width + wall*2 + 7.5, length/2 + wall, -1]) cylinder(h=wall+2, r=2.5);
        
        // --- Fessure per passaggio cavi (U-slots) ---
        // Foro Cavo Alimentazione USB (Lato corto inferiore)
        translate([width/2, -1, wall]) 
            cube([14, wall+2, 10]);
        // Foro Cavi Relè/Sensore (Lato corto superiore)
        translate([width/2, length+wall-1, wall]) 
            cube([18, wall+2, 10]);
        
        // Fori filettati nei 4 angoli
        translate([wall*2.5, wall*2.5, wall]) coarse_hole(height+1);
        translate([width - wall*0.5, wall*2.5, wall]) coarse_hole(height+1);
        translate([wall*2.5, length - wall*0.5, wall]) coarse_hole(height+1);
        translate([width - wall*0.5, length - wall*0.5, wall]) coarse_hole(height+1);
    }
}

module lid() {
    difference() {
        // Piastra di copertura
        cube([width + wall*2, length + wall*2, wall]);
        
        // Fori passanti lisci per il gambo delle viti
        translate([wall*2.5, wall*2.5, -1]) cylinder(h=wall+2, r=5.2);
        translate([width - wall*0.5, wall*2.5, -1]) cylinder(h=wall+2, r=5.2);
        translate([wall*2.5, length - wall*0.5, -1]) cylinder(h=wall+2, r=5.2);
        translate([width - wall*0.5, length - wall*0.5, -1]) cylinder(h=wall+2, r=5.2);
    }
}

module all_screws() {
    translate([0,0,0]) coarse_screw();
    translate([20,0,0]) coarse_screw();
    translate([40,0,0]) coarse_screw();
    translate([60,0,0]) coarse_screw();
}

// --- LOGICA DI RENDER ---
if (part == "assembly") {
    box();
    translate([0, 0, height+wall + 10]) lid();
    // Mostra le viti "esplose" in alto
    translate([wall*2.5, wall*2.5, height+wall + 20]) coarse_screw();
    translate([width - wall*0.5, wall*2.5, height+wall + 20]) coarse_screw();
    translate([wall*2.5, length - wall*0.5, height+wall + 20]) coarse_screw();
    translate([width - wall*0.5, length - wall*0.5, height+wall + 20]) coarse_screw();
} else if (part == "box") {
    box();
} else if (part == "lid") {
    lid();
} else if (part == "screws") {
    all_screws();
}
