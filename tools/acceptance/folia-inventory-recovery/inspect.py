import json
import sqlite3
import sys


database, purchase_id, player_id = sys.argv[1:]
connection = sqlite3.connect(f"file:{database}?mode=ro", uri=True)
connection.execute("PRAGMA query_only=ON")
connection.execute("BEGIN")
connection.row_factory = sqlite3.Row

purchase = connection.execute(
    "SELECT id,state,seller,item_count,price FROM purchase WHERE id=?",
    (purchase_id,),
).fetchone()
transfers = connection.execute(
    "SELECT id,purchase,player,state,note,before_hash,after_hash,created "
    "FROM item_transfer WHERE purchase=? AND player=? ORDER BY created",
    (purchase_id, player_id),
).fetchall()
settlements = connection.execute(
    "SELECT id,order_id,kind,state,amount,updated FROM settlement WHERE order_id=? ORDER BY updated,id",
    (purchase_id,),
).fetchall()

print(
    json.dumps(
        {
            "purchase": dict(purchase) if purchase else None,
            "transfers": [dict(row) for row in transfers],
            "settlements": [dict(row) for row in settlements],
        }
    )
)
connection.rollback()
connection.close()
