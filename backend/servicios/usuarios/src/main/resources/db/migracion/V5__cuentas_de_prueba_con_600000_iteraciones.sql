-- Las cuentas de prueba de V2 y V3 pasan a un hash de 600.000 iteraciones, las mismas que usan hoy las
-- cuentas nuevas (Contrasenas.ITERACIONES). Con 310.000 verificaban más rápido que el hash falso de un email
-- inexistente, y esa diferencia de tiempo dejaba ver que el email existe. La contraseña sigue siendo palco1234.
-- Solo se cambia la fila que todavía tiene el hash original: si alguien cambió esa contraseña, no se pisa.

update usuario set password_hash =
 'pbkdf2-sha256$600000$eBWMcPjCFkvFwNnriAuzqQ==$CpOr0nEnwnOlBWcF/TKl+OQChLB/SZBP4ZRsEJxmOTM='
 where id = 'user-titular' and password_hash = 'pbkdf2-sha256$310000$FpGLULULLzHBk0RIDgfaWQ==$sgFFg9euagg7EzRgMuR9tu4KCEmXJIRT8l//mZkMA/Q=';

update usuario set password_hash =
 'pbkdf2-sha256$600000$o7HyEMrvQMKRFjvh17+oFA==$EhXLd5vZNDBFigcnorP3IiIBJG6mmFEbh6FZnYBJiNg='
 where id = 'user-organizador' and password_hash = 'pbkdf2-sha256$310000$1TI/cQAHA7y8tJERoSL8Gw==$NuD50cY97EWw82FVmfOw5juUlhGqAMuNHAOzke+Ck1s=';

update usuario set password_hash =
 'pbkdf2-sha256$600000$SM5zqpdYR2iX3+uqA+ZxnA==$cr4QMZZqeBszk3et/XzJGD7dL8aOx+rFMM15RcMzw8w='
 where id = 'user-staff' and password_hash = 'pbkdf2-sha256$310000$rvbNSFOjTPB1XxoKKnUwIw==$LX6BnkeN6+pVhwsxXswAfFOahBaLUoSOR58h3Mwfqbw=';

update usuario set password_hash =
 'pbkdf2-sha256$600000$jKUVoXCLTp+7w4MwEv3SvA==$/x8uLu10p6rNjOrbvo0dNIFwZL3hhZ4GAOzMrOrAgvM='
 where id = 'user-otro-titular' and password_hash = 'pbkdf2-sha256$310000$FpGLULULLzHBk0RIDgfaWQ==$sgFFg9euagg7EzRgMuR9tu4KCEmXJIRT8l//mZkMA/Q=';
