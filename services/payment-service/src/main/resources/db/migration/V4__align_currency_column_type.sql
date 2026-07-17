-- ═══════════════════════════════════════════════════════════════════════════
-- Align currency columns with the rest of the platform: CHAR(3) -> VARCHAR(3)
--
-- payment-service could not boot at all. With ddl-auto=validate, Hibernate
-- compares the JDBC type code: an unqualified String field maps to VARCHAR, while
-- CHAR(3) reports as bpchar (Types#CHAR), so validation failed and the
-- EntityManagerFactory was never built:
--
--   Schema-validation: wrong column type encountered in column [currency] in
--   table [nach_mandates]; found [bpchar (Types#CHAR)], but expecting
--   [varchar(255) (Types#VARCHAR)]
--
-- Every other service already declares currency VARCHAR(3) — ledger, lms, los,
-- template — with the identical `@Column private String currency` mapping, and
-- boots. payment's CHAR(3) was the sole outlier platform-wide. Note Hibernate
-- validates the type code and not the length, which is why VARCHAR(3) satisfies an
-- unqualified String while bpchar cannot.
--
-- Forward migration rather than an edit to V1: Flyway runs before Hibernate
-- validation, so V1 is already applied in any database where payment was started,
-- and editing it would break the checksum.
--
-- 'INR' values and the NOT NULL DEFAULT survive the cast; VARCHAR(3) keeps the
-- 3-character ceiling, so no data widens. Note the semantic change is real but
-- desirable: bpchar blank-pads to a fixed width, varchar does not.
-- ═══════════════════════════════════════════════════════════════════════════

ALTER TABLE payment_orders ALTER COLUMN currency TYPE VARCHAR(3);
ALTER TABLE nach_mandates  ALTER COLUMN currency TYPE VARCHAR(3);
