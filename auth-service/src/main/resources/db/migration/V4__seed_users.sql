INSERT INTO users (
    name,
    email,
    password,
    role_id
)
SELECT
    'Administrador',
    'admin@hospital.com',
    '$2a$10$qJ7yuST6HN37XHc8VYC3jO03NXVYJtJiZMWUwkQrcc5WAQYHhI/3m', --Admin@123
    id
FROM roles
WHERE name = 'ADMIN';

INSERT INTO users (
    name,
    email,
    password,
    role_id
)
SELECT
    'Dr. João Silva',
    'joao.silva@hospital.com',
    '$2a$10$X9U1ml.bqfQ19yPAsmvDkOY2hlLFcOLwpVQa2zbOR4hat.N6P07WK', --Doutor@123
    id
FROM roles
WHERE name = 'DOCTOR';

INSERT INTO users (
    name,
    email,
    password,
    role_id
)
SELECT
    'Maria Santos',
    'maria.santos@hospital.com',
    '$2a$10$3jKqZdhIPBXTdGKv7bLiP.DmQknozD7ssfJjQgQh9u2gRTtjh0gna', --Enfermeira@123
    id
FROM roles
WHERE name = 'NURSE';

INSERT INTO users (
    name,
    email,
    password,
    role_id
)
SELECT
    'Lucas Oliveira',
    'lucas.oliveira@hospital.com',
    '$2a$10$OE4Ko/2iAtgY2SXXNhn2a.RYd26Tk2wFcOO5KqryL6SwXmR52GLSa', --Paciente@123
    id
FROM roles
WHERE name = 'PATIENT';