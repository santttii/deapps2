-- Una segunda cuenta compradora de prueba, para mostrar el aislamiento entre usuarios: con su token no se
-- ve ninguna compra de titular@palco.test. Mismo formato que las de V2. Contraseña: palco1234 (el hash es
-- el de titular@palco.test).

insert into usuario (id, nombre, apellido, dni, email, password_hash, rol,
                     email_verificado, identidad_verificada, creado) values
('user-otro-titular', 'Julián', 'Ferreyra', '30555444', 'otro.titular@palco.test',
 'pbkdf2-sha256$310000$FpGLULULLzHBk0RIDgfaWQ==$sgFFg9euagg7EzRgMuR9tu4KCEmXJIRT8l//mZkMA/Q=',
 'user', true, true, '2026-09-01 00:00:00');
