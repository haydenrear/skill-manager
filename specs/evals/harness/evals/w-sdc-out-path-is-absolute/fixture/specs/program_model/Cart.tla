---- MODULE Cart ----
EXTENDS Naturals
VARIABLES qty
Init == qty = 0
AddItem == qty < 2 /\ qty' = qty + 1
Next == AddItem
Spec == Init /\ [][Next]_qty
====
