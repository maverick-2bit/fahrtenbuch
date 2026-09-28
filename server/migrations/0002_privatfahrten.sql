-- Privatfahrten mit persönlichem PIN (Ende-zu-Ende-verschlüsselt)
-- fahrer.schluessel: Datenschlüssel des Fahrers, verschlüsselt mit einem Schlüssel aus seinem PIN
--                    (JSON {v, salt, iter, iv, ct}); der PIN selbst ist dem Server nie bekannt.
-- fahrten.geheim:    verschlüsselte Details einer Privatfahrt (JSON {v, iv, ct}); Adressen, Zwischenziele,
--                    Notiz und Koordinaten stehen dann nicht im Klartext in der Datenbank.
ALTER TABLE fahrer ADD COLUMN schluessel TEXT;
ALTER TABLE fahrten ADD COLUMN geheim TEXT;
