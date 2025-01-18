(ns tech.jgood.gleanmo.schema.meta)

(def legacy-meta
  [[:tech.jgood.gleanmo.schema/created-at {:optional true} :instant]
   [:tech.jgood.gleanmo.schema/deleted-at {:optional true} :instant]
   [:tech.jgood.gleanmo.schema/type {:optional true} :keyword]])
