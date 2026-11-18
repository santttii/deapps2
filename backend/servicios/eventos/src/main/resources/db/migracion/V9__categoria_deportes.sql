-- La categoría "Fútbol" pasa a llamarse "Deportes": el catálogo ya la mostraba así en el filtro, y un
-- organizador no tenía dónde poner un evento deportivo que no fuera de fútbol (Fórmula 1, tenis, básquet).
-- La columna guarda la etiqueta (Categoria.ConversorJpa), así que se actualizan los eventos que ya existen.
-- SQL compatible con PostgreSQL (Supabase) y con H2 en modo PostgreSQL.

update evento set categoria = 'Deportes' where categoria = 'Fútbol';
