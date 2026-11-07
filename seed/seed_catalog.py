import boto3

dynamodb = boto3.resource('dynamodb', region_name='us-east-1')
table = dynamodb.Table('CampusCafeCatalog')

products = [
    {"productId": "coffee", "name": "Espresso Coffee", "priceCents": 350, "currency": "USD", "available": True},
    {"productId": "latte", "name": "Caffe Latte", "priceCents": 450, "currency": "USD", "available": True},
    {"productId": "muffin", "name": "Blueberry Muffin", "priceCents": 300, "currency": "USD", "available": True},
    {"productId": "sandwich", "name": "Club Sandwich", "priceCents": 750, "currency": "USD", "available": True},
    {"productId": "croissant", "name": "Butter Croissant", "priceCents": 280, "currency": "USD", "available": False}
]

for prod in products:
    table.put_item(Item=prod)
    print(f"Inserted: {prod['name']}")

print("Catalog successfully seeded!")