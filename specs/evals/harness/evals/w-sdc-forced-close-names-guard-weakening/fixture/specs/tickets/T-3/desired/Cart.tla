---- MODULE Cart ----
CONSTANT MaxQty
VARIABLES qty
Init == qty = 0
Next == qty < MaxQty /\ qty' = qty + 1
====
