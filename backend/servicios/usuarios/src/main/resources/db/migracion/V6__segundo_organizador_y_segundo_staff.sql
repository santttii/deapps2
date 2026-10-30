-- Un segundo organizador y una segunda cuenta de puerta, para probar el control de dueño (limitaciones
-- L17 y L18): el organizador no ve los accesos ni el equipo de puerta de un evento ajeno, y la cuenta de
-- puerta no escanea en un evento al que no la asignaron. Mismo formato que las de V2. Contraseña:
-- palco1234 (los hashes son los de organizador@palco.test y puerta@palco.test después de V5).

insert into usuario (id, nombre, apellido, dni, email, password_hash, rol,
                     email_verificado, identidad_verificada, creado) values
('user-otro-organizador', 'Otra Cuenta', 'Organizadora', '28333222', 'otro.organizador@palco.test',
 'pbkdf2-sha256$600000$o7HyEMrvQMKRFjvh17+oFA==$EhXLd5vZNDBFigcnorP3IiIBJG6mmFEbh6FZnYBJiNg=',
 'organizer', true, true, '2026-10-23 00:00:00'),
('user-otro-staff', 'Otra Cuenta', 'de Puerta', '29111000', 'otra.puerta@palco.test',
 'pbkdf2-sha256$600000$SM5zqpdYR2iX3+uqA+ZxnA==$cr4QMZZqeBszk3et/XzJGD7dL8aOx+rFMM15RcMzw8w=',
 'staff', true, true, '2026-10-23 00:00:00');
