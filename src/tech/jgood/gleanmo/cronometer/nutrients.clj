(ns tech.jgood.gleanmo.cronometer.nutrients
  "Cronometer nutrient id -> Gleanmo key, label, unit and category.

   Generated once from Cronometer's nutrient definition catalog
   (`get_nutrients`, 95 entries) and committed, so an upstream rename never
   moves stored data. Nutrient maps on `food` and `food-log` are keyed by
   `:key` and carry no units; this table is where the units live. That is the
   stated exception to the sibling `-unit` field rule in AGENTS.md: a unit
   beside each of ~95 values per row would only repeat this table.

   Ids absent from the table (negative ids other than -1205, USDA fatty-acid
   ids from 652 up) are kept in the raw payload only.

   Keys are the kebab-cased name with punctuation folded:
   \"B1 (Thiamine)\" -> `:b1-thiamine`, \"Lutein+Zeaxanthin\" ->
   `:lutein-zeaxanthin`.")

(def by-id
  "Cronometer nutrient id -> `{:key :label :unit :category}`."
  {-1205  {:key :net-carbs               :label "Net Carbs"                :unit :g    :category "Carbohydrates"}
   203    {:key :protein                 :label "Protein"                  :unit :g    :category "Protein"}
   204    {:key :fat                     :label "Fat"                      :unit :g    :category "Lipids"}
   205    {:key :carbs                   :label "Carbs"                    :unit :g    :category "Carbohydrates"}
   207    {:key :ash                     :label "Ash"                      :unit :g    :category "General"}
   208    {:key :energy                  :label "Energy"                   :unit :kcal :category "General"}
   209    {:key :starch                  :label "Starch"                   :unit :g    :category "Carbohydrates"}
   210    {:key :sucrose                 :label "Sucrose"                  :unit :g    :category "Carbohydrates"}
   211    {:key :glucose                 :label "Glucose"                  :unit :g    :category "Carbohydrates"}
   212    {:key :fructose                :label "Fructose"                 :unit :g    :category "Carbohydrates"}
   213    {:key :lactose                 :label "Lactose"                  :unit :g    :category "Carbohydrates"}
   214    {:key :maltose                 :label "Maltose"                  :unit :g    :category "Carbohydrates"}
   221    {:key :alcohol                 :label "Alcohol"                  :unit :g    :category "General"}
   246    {:key :phytate                 :label "Phytate"                  :unit :mg   :category "General"}
   255    {:key :water                   :label "Water"                    :unit :g    :category "General"}
   262    {:key :caffeine                :label "Caffeine"                 :unit :mg   :category "General"}
   269    {:key :sugars                  :label "Sugars"                   :unit :g    :category "Carbohydrates"}
   287    {:key :galactose               :label "Galactose"                :unit :g    :category "Carbohydrates"}
   291    {:key :fiber                   :label "Fiber"                    :unit :g    :category "Carbohydrates"}
   295    {:key :soluble-fiber           :label "Soluble Fiber"            :unit :g    :category "Carbohydrates"}
   297    {:key :insoluble-fiber         :label "Insoluble Fiber"          :unit :g    :category "Carbohydrates"}
   301    {:key :calcium                 :label "Calcium"                  :unit :mg   :category "Minerals"}
   303    {:key :iron                    :label "Iron"                     :unit :mg   :category "Minerals"}
   304    {:key :magnesium               :label "Magnesium"                :unit :mg   :category "Minerals"}
   305    {:key :phosphorus              :label "Phosphorus"               :unit :mg   :category "Minerals"}
   306    {:key :potassium               :label "Potassium"                :unit :mg   :category "Minerals"}
   307    {:key :sodium                  :label "Sodium"                   :unit :mg   :category "Minerals"}
   309    {:key :zinc                    :label "Zinc"                     :unit :mg   :category "Minerals"}
   312    {:key :copper                  :label "Copper"                   :unit :mg   :category "Minerals"}
   313    {:key :fluoride                :label "Fluoride"                 :unit :ug   :category "Minerals"}
   315    {:key :manganese               :label "Manganese"                :unit :mg   :category "Minerals"}
   317    {:key :selenium                :label "Selenium"                 :unit :ug   :category "Minerals"}
   319    {:key :retinol                 :label "Retinol"                  :unit :ug   :category "Vitamins"}
   320    {:key :vitamin-a               :label "Vitamin A"                :unit :ug   :category "Vitamins"}
   321    {:key :beta-carotene           :label "Beta-carotene"            :unit :ug   :category "Vitamins"}
   322    {:key :alpha-carotene          :label "Alpha-carotene"           :unit :ug   :category "Vitamins"}
   323    {:key :vitamin-e               :label "Vitamin E"                :unit :mg   :category "Vitamins"}
   324    {:key :vitamin-d               :label "Vitamin D"                :unit :iu   :category "Vitamins"}
   334    {:key :beta-cryptoxanthin      :label "Beta-cryptoxanthin"       :unit :ug   :category "Vitamins"}
   337    {:key :lycopene                :label "Lycopene"                 :unit :ug   :category "Vitamins"}
   338    {:key :lutein-zeaxanthin       :label "Lutein+Zeaxanthin"        :unit :ug   :category "Vitamins"}
   341    {:key :beta-tocopherol         :label "Beta Tocopherol"          :unit :mg   :category "Vitamins"}
   342    {:key :gamma-tocopherol        :label "Gamma Tocopherol"         :unit :mg   :category "Vitamins"}
   343    {:key :delta-tocopherol        :label "Delta Tocopherol"         :unit :mg   :category "Vitamins"}
   401    {:key :vitamin-c               :label "Vitamin C"                :unit :mg   :category "Vitamins"}
   404    {:key :b1-thiamine             :label "B1 (Thiamine)"            :unit :mg   :category "Vitamins"}
   405    {:key :b2-riboflavin           :label "B2 (Riboflavin)"          :unit :mg   :category "Vitamins"}
   406    {:key :b3-niacin               :label "B3 (Niacin)"              :unit :mg   :category "Vitamins"}
   410    {:key :b5-pantothenic-acid     :label "B5 (Pantothenic Acid)"    :unit :mg   :category "Vitamins"}
   415    {:key :b6-pyridoxine           :label "B6 (Pyridoxine)"          :unit :mg   :category "Vitamins"}
   417    {:key :folate                  :label "Folate"                   :unit :ug   :category "Vitamins"}
   418    {:key :b12-cobalamin           :label "B12 (Cobalamin)"          :unit :ug   :category "Vitamins"}
   421    {:key :choline                 :label "Choline"                  :unit :mg   :category "Vitamins"}
   430    {:key :vitamin-k               :label "Vitamin K"                :unit :ug   :category "Vitamins"}
   501    {:key :tryptophan              :label "Tryptophan"               :unit :g    :category "Protein"}
   502    {:key :threonine               :label "Threonine"                :unit :g    :category "Protein"}
   503    {:key :isoleucine              :label "Isoleucine"               :unit :g    :category "Protein"}
   504    {:key :leucine                 :label "Leucine"                  :unit :g    :category "Protein"}
   505    {:key :lysine                  :label "Lysine"                   :unit :g    :category "Protein"}
   506    {:key :methionine              :label "Methionine"               :unit :g    :category "Protein"}
   507    {:key :cystine                 :label "Cystine"                  :unit :g    :category "Protein"}
   508    {:key :phenylalanine           :label "Phenylalanine"            :unit :g    :category "Protein"}
   509    {:key :tyrosine                :label "Tyrosine"                 :unit :g    :category "Protein"}
   510    {:key :valine                  :label "Valine"                   :unit :g    :category "Protein"}
   511    {:key :arginine                :label "Arginine"                 :unit :g    :category "Protein"}
   512    {:key :histidine               :label "Histidine"                :unit :g    :category "Protein"}
   513    {:key :alanine                 :label "Alanine"                  :unit :g    :category "Protein"}
   514    {:key :aspartic-acid           :label "Aspartic acid"            :unit :g    :category "Protein"}
   515    {:key :glutamic-acid           :label "Glutamic acid"            :unit :g    :category "Protein"}
   516    {:key :glycine                 :label "Glycine"                  :unit :g    :category "Protein"}
   517    {:key :proline                 :label "Proline"                  :unit :g    :category "Protein"}
   518    {:key :serine                  :label "Serine"                   :unit :g    :category "Protein"}
   521    {:key :hydroxyproline          :label "Hydroxyproline"           :unit :g    :category "Protein"}
   601    {:key :cholesterol             :label "Cholesterol"              :unit :mg   :category "Lipids"}
   605    {:key :trans-fats              :label "Trans-Fats"               :unit :g    :category "Lipids"}
   606    {:key :saturated               :label "Saturated"                :unit :g    :category "Lipids"}
   621    {:key :dha                     :label "DHA"                      :unit :g    :category "Lipids"}
   629    {:key :epa                     :label "EPA"                      :unit :g    :category "Lipids"}
   636    {:key :phytosterol             :label "Phytosterol"              :unit :mg   :category "Lipids"}
   645    {:key :monounsaturated         :label "Monounsaturated"          :unit :g    :category "Lipids"}
   646    {:key :polyunsaturated         :label "Polyunsaturated"          :unit :g    :category "Lipids"}
   675    {:key :la                      :label "LA"                       :unit :g    :category "Lipids"}
   851    {:key :ala                     :label "ALA"                      :unit :g    :category "Lipids"}
   853    {:key :aa                      :label "AA"                       :unit :g    :category "Lipids"}
   10001  {:key :omega-3                 :label "Omega-3"                  :unit :g    :category "Lipids"}
   10002  {:key :omega-6                 :label "Omega-6"                  :unit :g    :category "Lipids"}
   10003  {:key :chromium                :label "Chromium"                 :unit :ug   :category "Minerals"}
   10004  {:key :biotin                  :label "Biotin"                   :unit :ug   :category "Vitamins"}
   10005  {:key :iodine                  :label "Iodine"                   :unit :ug   :category "Minerals"}
   10007  {:key :sugar-alcohol           :label "Sugar Alcohol"            :unit :g    :category "Carbohydrates"}
   10008  {:key :molybdenum              :label "Molybdenum"               :unit :ug   :category "Minerals"}
   10009  {:key :added-sugars            :label "Added Sugars"             :unit :g    :category "Carbohydrates"}
   10010  {:key :allulose                :label "Allulose"                 :unit :g    :category "Carbohydrates"}
   10011  {:key :beta-hydroxybutyrate    :label "Beta-Hydroxybutyrate"     :unit :g    :category "General"}
   10012  {:key :oxalate                 :label "Oxalate"                  :unit :mg   :category "General"}})

(def by-key
  "Nutrient key -> `{:id :key :label :unit :category}`."
  (into {}
        (map (fn [[id row]] [(:key row) (assoc row :id id)]))
        by-id))

(def ordered
  "Every nutrient row with its Cronometer id, ordered by category then id, for
   publishing the table."
  (->> by-id
       (map (fn [[id row]] (assoc row :id id)))
       (sort-by (juxt :category :id))
       vec))
