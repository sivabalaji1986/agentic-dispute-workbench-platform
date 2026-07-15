INSERT INTO workbench.cases VALUES
  ('D-10291', 'I paid SGD 250 for an item, but I never received it. The merchant says the item was delivered, but I disagree.',
   'GOODS_NOT_RECEIVED', 'OPEN', 250.00, 'SGD', NOW(), NOW());

INSERT INTO workbench.transactions VALUES
  ('TXN-55820', 'D-10291', 250.00, 'SGD', 'ShopFast Pte Ltd', '2026-07-01', 'Item was delivered');

INSERT INTO workbench.evidence_documents VALUES
  (DEFAULT, 'D-10291', 'TRANSACTION_RECORD', TRUE, NOW()),
  (DEFAULT, 'D-10291', 'MERCHANT_RESPONSE',  TRUE, NOW());
-- customer_declaration and delivery_proof are intentionally absent
