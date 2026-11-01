package com.example.aarogya.data

/** Broad food types for the "By food type" summary. */
enum class FoodCategory(val label: String) {
    DRINKS("Drinks"),
    SWEETS("Sweets & desserts"),
    SNACKS("Snacks & fried"),
    FAST_FOOD("Fast food & street food"),
    MEAT("Meat, fish & eggs"),
    LEGUMES("Dals & legumes"),
    GRAINS("Grains, rice & breads"),
    VEG_CURRIES("Veg curries & paneer"),
    DAIRY("Dairy"),
    FRUITS("Fruits"),
    VEGETABLES("Vegetables & salads"),
    OTHER("Other"),
}

/**
 * Keyword rules, checked in [FoodCategory] order so the more specific type wins: "milkshake" is a
 * drink before it's dairy, "rice pudding" a sweet before it's a grain, "chicken biryani" meat before rice.
 * Works on any name (food-table entries and Gemini's free-form names alike).
 */
object FoodCategories {
    private val RULES: Map<FoodCategory, List<String>> = mapOf(
        FoodCategory.DRINKS to listOf(
            "coffee", "tea", "chai", "latte", "cappuccino", "espresso", "americano", "cola", "coke", "pepsi", "soda", "soft drink",
            "juice", "smoothie", "milkshake", "shake", "lassi", "buttermilk", "chaas", "beer", "wine", "whisky", "whiskey", "rum",
            "vodka", "gin", "brandy", "water", "horchata", "energy drink", "red bull", "falooda",
        ),
        FoodCategory.SWEETS to listOf(
            "gulab jamun", "jalebi", "imarti", "ladoo", "laddu", "kheer", "payasam", "rice pudding", "halwa", "barfi", "burfi",
            "katli", "rasgulla", "rasmalai", "ras malai", "cake", "pastry", "ice cream", "kulfi", "chocolate", "donut",
            "doughnut", "churro", "flan", "tres leches", "shrikhand", "sugar", "honey", "jam", "muffin", "cupcake", "cookie",
            "biscuit", "pan dulce", "concha", "dessert", "sweet", "mithai", "peda", "sandesh", "pancake", "waffle", "croissant",
        ),
        FoodCategory.SNACKS to listOf(
            "samosa", "pakora", "pakoda", "bhaji", "bhajiya", "chips", "crisps", "wafers", "fries", "namkeen", "bhujia",
            "mixture", "chivda", "kachori", "vada", "chaat", "pani puri", "golgappa", "puchka", "bhel", "sev puri", "dahi puri",
            "papdi", "dhokla", "khandvi", "momo", "dumpling", "spring roll", "nachos", "popcorn", "makhana", "peanut",
            "almond", "cashew", "walnut", "nuts", "protein bar", "granola bar", "energy bar", "aloo tikki", "tikki", "manchurian",
        ),
        FoodCategory.FAST_FOOD to listOf(
            "pizza", "burger", "sandwich", "wrap", "kathi roll", "frankie", "taco", "burrito", "quesadilla", "enchilada",
            "chimichanga", "tostada", "tamale", "gordita", "chalupa", "sope", "huarache", "tlayuda", "torta", "pambazo",
            "taquito", "flauta", "hot dog", "pav bhaji", "vada pav", "misal", "thali", "chilaquiles", "nacho",
        ),
        FoodCategory.MEAT to listOf(
            "chicken", "mutton", "lamb", "goat", "fish", "salmon", "tuna", "prawn", "shrimp", "egg", "omelette", "omelet",
            "keema", "kheema", "kebab", "kabab", "pork", "beef", "carnitas", "barbacoa", "birria", "al pastor", "carne asada",
            "asada", "chorizo", "rogan josh", "vindaloo", "pibil", "ceviche", "pozole", "menudo", "mole", "fajita", "bacon",
            "sausage", "turkey", "huevos", "steak", "ham", "meatball", "wings", "tandoori", "tikka",
        ),
        FoodCategory.LEGUMES to listOf(
            "dal", "daal", "dhal", "lentil", "sambar", "rasam", "rajma", "kidney bean", "chole", "chana", "chickpea", "beans",
            "frijoles", "sprouts", "kadhi", "tofu", "soy", "soya", "hummus", "moong", "toor", "pesarattu",
        ),
        FoodCategory.GRAINS to listOf(
            "roti", "chapati", "chapathi", "phulka", "paratha", "parantha", "naan", "puri", "poori", "bhatura", "bread", "toast",
            "kulcha", "thepla", "thalipeeth", "pav", "tortilla", "rice", "biryani", "pulao", "pulav", "pilaf", "khichdi",
            "poha", "upma", "idli", "dosa", "uttapam", "appam", "idiyappam", "puttu", "pongal", "oats", "oatmeal", "porridge",
            "cereal", "cornflakes", "muesli", "granola", "pasta", "spaghetti", "macaroni", "noodles", "maggi", "ramen",
            "quinoa", "ragi", "mudde", "bajra", "jowar", "millet",
        ),
        FoodCategory.VEG_CURRIES to listOf(
            "paneer", "sabzi", "sabji", "subzi", "curry", "korma", "kofta", "aloo", "gobi", "bhindi", "okra", "baingan",
            "brinjal", "bharta", "saag", "sarson", "palak", "matar", "dum aloo", "mix veg", "masala",
        ),
        FoodCategory.DAIRY to listOf("milk", "curd", "yogurt", "yoghurt", "dahi", "raita", "cheese", "butter", "ghee", "cream", "queso"),
        FoodCategory.FRUITS to listOf(
            "apple", "banana", "orange", "mango", "grape", "papaya", "watermelon", "melon", "pineapple", "pomegranate", "guava",
            "strawberr", "berry", "berries", "pear", "chikoo", "sapota", "date", "raisin", "coconut", "fruit", "lime", "lemon",
            "kiwi", "peach", "plum",
        ),
        FoodCategory.VEGETABLES to listOf(
            "salad", "cucumber", "tomato", "carrot", "broccoli", "spinach", "corn", "elote", "esquites", "potato", "mushroom",
            "soup", "radish", "onion", "pepper", "cabbage", "beet", "zucchini", "lettuce", "kosambari", "guacamole", "salsa",
            "pico de gallo", "vegetable", "veggie",
        ),
    )

    fun categorize(name: String): FoodCategory {
        val n = " " + name.lowercase().replace(Regex("[^a-z ]"), " ").replace(Regex("\\s+"), " ").trim() + " "
        for ((category, words) in RULES) {
            // Whole-word / word-prefix match: "tea" must not hit "steak", "date" must not hit "update".
            if (words.any { w -> n.contains(" $w") && (n.contains(" $w ") || n.contains(" ${w}s ") || n.contains(" ${w}es ") || w.endsWith("berr")) }) return category
        }
        return FoodCategory.OTHER
    }
}
