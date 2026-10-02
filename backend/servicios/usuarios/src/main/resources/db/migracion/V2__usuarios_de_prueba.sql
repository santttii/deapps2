-- Las 3 cuentas de prueba del README. Contraseña de todas: palco1234.

insert into usuario (id, nombre, apellido, dni, email, password_hash, rol,
                     email_verificado, identidad_verificada, creado) values
('user-titular', 'Martina', 'Suárez', '29888777', 'titular@palco.test',
 'pbkdf2-sha256$310000$FpGLULULLzHBk0RIDgfaWQ==$sgFFg9euagg7EzRgMuR9tu4KCEmXJIRT8l//mZkMA/Q=',
 'user', true, true, '2026-09-01 00:00:00'),
('user-organizador', 'Cuenta', 'Organizadora', '27444555', 'organizador@palco.test',
 'pbkdf2-sha256$310000$1TI/cQAHA7y8tJERoSL8Gw==$NuD50cY97EWw82FVmfOw5juUlhGqAMuNHAOzke+Ck1s=',
 'organizer', true, true, '2026-09-01 00:00:00'),
('user-staff', 'Cuenta', 'de Puerta', '29666777', 'puerta@palco.test',
 'pbkdf2-sha256$310000$rvbNSFOjTPB1XxoKKnUwIw==$LX6BnkeN6+pVhwsxXswAfFOahBaLUoSOR58h3Mwfqbw=',
 'staff', true, true, '2026-09-01 00:00:00');
