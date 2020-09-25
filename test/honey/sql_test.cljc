;; copyright (c) sean corfield, all rights reserved

(ns honey.sql-test
  (:refer-clojure :exclude [format])
  (:require #?(:clj [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test :refer-macros [deftest is testing]])
            [honey.sql :as sut :refer [format]]))

(deftest mysql-tests
  (is (= ["SELECT * FROM `table` WHERE `id` = ?" 1]
         (sut/format {:select [:*] :from [:table] :where [:= :id 1]}
                     {:dialect :mysql}))))

(deftest expr-tests
  (is (= ["id IS NULL"]
         (sut/format-expr [:= :id nil])))
  (is (= ["id IS NULL"]
         (sut/format-expr [:is :id nil])))
  (is (= ["id IS NOT NULL"]
         (sut/format-expr [:<> :id nil])))
  (is (= ["id IS NOT NULL"]
         (sut/format-expr [:!= :id nil])))
  (is (= ["id IS NOT NULL"]
         (sut/format-expr [:is-not :id nil])))
  ;; degenerate cases:
  (is (= ["NULL IS NULL"]
         (sut/format-expr [:= nil nil])))
  (is (= ["NULL IS NOT NULL"]
         (sut/format-expr [:<> nil nil])))
  (is (= ["id = ?" 1]
         (sut/format-expr [:= :id 1])))
  (is (= ["id + ?" 1]
         (sut/format-expr [:+ :id 1])))
  (is (= ["? + (? + quux)" 1 1]
         (sut/format-expr [:+ 1 [:+ 1 :quux]])))
  (is (= ["FOO(BAR(? + G(abc)), F(?, quux))" 2 1]
         (sut/format-expr [:foo [:bar [:+ 2 [:g :abc]]] [:f 1 :quux]])))
  (is (= ["id"]
         (sut/format-expr :id)))
  (is (= ["?" 1]
         (sut/format-expr 1)))
  (is (= ["INTERVAL ? DAYS" 30]
         (sut/format-expr [:interval 30 :days]))))

(deftest where-test
  (is (= ["WHERE id = ?" 1]
         (#'sut/format-on-expr :where [:= :id 1]))))

(deftest general-tests
  (is (= ["SELECT * FROM \"table\" WHERE \"id\" = ?" 1]
         (sut/format {:select [:*] :from [:table] :where [:= :id 1]} {:quoted true})))
  ;; temporarily remove AS from alias here
  (is (= ["SELECT \"t\".* FROM \"table\" \"t\" WHERE \"id\" = ?" 1]
         (sut/format {:select [:t.*] :from [[:table :t]] :where [:= :id 1]} {:quoted true})))
  (is (= ["SELECT * FROM \"table\" GROUP BY \"foo\", \"bar\""]
         (sut/format {:select [:*] :from [:table] :group-by [:foo :bar]} {:quoted true})))
  (is (= ["SELECT * FROM \"table\" GROUP BY DATE(\"bar\")"]
         (sut/format {:select [:*] :from [:table] :group-by [[:date :bar]]} {:quoted true})))
  (is (= ["SELECT * FROM \"table\" ORDER BY \"foo\" DESC, \"bar\" ASC"]
         (sut/format {:select [:*] :from [:table] :order-by [[:foo :desc] :bar]} {:quoted true})))
  (is (= ["SELECT * FROM \"table\" ORDER BY DATE(\"expiry\") DESC, \"bar\" ASC"]
         (sut/format {:select [:*] :from [:table] :order-by [[[:date :expiry] :desc] :bar]} {:quoted true})))
  (is (= ["SELECT * FROM \"table\" WHERE DATE_ADD(\"expiry\", INTERVAL ? DAYS) < NOW()" 30]
         (sut/format {:select [:*] :from [:table] :where [:< [:date_add :expiry [:interval 30 :days]] [:now]]} {:quoted true})))
  (is (= ["SELECT * FROM `table` WHERE `id` = ?" 1]
         (sut/format {:select [:*] :from [:table] :where [:= :id 1]} {:dialect :mysql})))
  (is (= ["SELECT * FROM \"table\" WHERE \"id\" IN (?, ?, ?, ?)" 1 2 3 4]
         (sut/format {:select [:*] :from [:table] :where [:in :id [1 2 3 4]]} {:quoted true}))))

;; tests lifted from HoneySQL v1 to check for compatibility


(deftest alias-splitting
  (is (= ["SELECT `aa`.`c` AS `a.c`, `bb`.`c` AS `b.c`, `cc`.`c` AS `c.c`"]
         (format {:select [[:aa.c "a.c"]
                           [:bb.c :b.c]
                           [:cc.c 'c.c]]}
                 {:dialect :mysql}))
      "aliases containing \".\" are quoted as necessary but not split"))

(deftest values-alias
  (is (= ["SELECT vals.a FROM (VALUES (?, ?, ?)) vals (a, b, c)" 1 2 3]
         (format {:select [:vals.a]
                  :from [[{:values [[1 2 3]]} [:vals {:columns [:a :b :c]}]]]}))))
(deftest test-cte
  (is (= (format {:with [[:query {:select [:foo] :from [:bar]}]]})
         ["WITH query AS (SELECT foo FROM bar)"]))
  (is (= (format {:with-recursive [[:query {:select [:foo] :from [:bar]}]]})
         ["WITH RECURSIVE query AS (SELECT foo FROM bar)"]))
  (is (= (format {:with [[[:static {:columns [:a :b :c]}] {:values [[1 2 3] [4 5 6]]}]]})
         ["WITH static (a, b, c) AS (VALUES (?, ?, ?), (?, ?, ?))" 1 2 3 4 5 6]))
  (is (= (format
           {:with [[[:static {:columns [:a :b :c]}]
                    {:values [[1 2 3] [4 5 6]]}]]
            :select [:*]
            :from [:static]})
         ["WITH static (a, b, c) AS (VALUES (?, ?, ?), (?, ?, ?)) SELECT * FROM static" 1 2 3 4 5 6])))

(deftest insert-into
  (is (= (format {:insert-into :foo})
         ["INSERT INTO foo"]))
  (is (= (format {:insert-into [:foo {:select [:bar] :from [:baz]}]})
         ["INSERT INTO foo SELECT bar FROM baz"]))
  (is (= (format {:insert-into [[:foo [:a :b :c]] {:select [:d :e :f] :from [:baz]}]})
         ["INSERT INTO foo (a, b, c) SELECT d, e, f FROM baz"]))
  (is (= (format {:insert-into [[:foo [:a :b :c]] {:select [:d :e :f] :from [:baz]}]})
         ["INSERT INTO foo (a, b, c) SELECT d, e, f FROM baz"])))

(deftest insert-into-namespaced
  ;; un-namespaced: works as expected:
  (is (= (format {:insert-into :foo :values [{:foo/id 1}]})
         ["INSERT INTO foo (id) VALUES (?)" 1]))
  (is (= (format {:insert-into :foo :columns [:foo/id] :values [[2]]})
         ["INSERT INTO foo (id) VALUES (?)" 2]))
  (is (= (format {:insert-into :foo :values [{:foo/id 1}]}
                 {:namespace-as-table? true})
         ["INSERT INTO foo (id) VALUES (?)" 1]))
  (is (= (format {:insert-into :foo :columns [:foo/id] :values [[2]]}
                 {:namespace-as-table? true})
         ["INSERT INTO foo (id) VALUES (?)" 2])))

(deftest exists-test
  ;; EXISTS should never have been implemented as SQL syntax: it's an operator!
  #_(is (= (format {:exists {:select [:a] :from [:foo]}})
           ["EXISTS (SELECT a FROM foo)"]))
  ;; select function call with an alias:
  (is (= (format {:select [[[:exists {:select [:a] :from [:foo]}] :x]]})
         ["SELECT EXISTS (SELECT a FROM foo) AS x"]))
  ;; select function call with no alias required:
  (is (= (format {:select [[[:exists {:select [:a] :from [:foo]}]]]})
         ["SELECT EXISTS (SELECT a FROM foo)"]))
  (is (= (format {:select [:id]
                  :from [:foo]
                  :where [:exists {:select [1]
                                   :from [:bar]
                                   :where :deleted}]})
         ["SELECT id FROM foo WHERE EXISTS (SELECT ? FROM bar WHERE deleted)" 1])))

(deftest array-test
  (is (= (format {:insert-into :foo
                  :columns [:baz]
                  :values [[[:array [1 2 3 4]]]]})
         ["INSERT INTO foo (baz) VALUES (ARRAY[?, ?, ?, ?])" 1 2 3 4]))
  (is (= (format {:insert-into :foo
                  :columns [:baz]
                  :values [[[:array ["one" "two" "three"]]]]})
         ["INSERT INTO foo (baz) VALUES (ARRAY[?, ?, ?])" "one" "two" "three"])))

(deftest union-test
  ;; UNION and INTERSECT subexpressions should not be parenthesized.
  ;; If you need to add more complex expressions, use a subquery like this:
  ;;   SELECT foo FROM bar1
  ;;   UNION
  ;;   SELECT foo FROM (SELECT foo FROM bar2 ORDER BY baz LIMIT 2)
  ;;   ORDER BY foo ASC
  (is (= (format {:union [{:select [:foo] :from [:bar1]}
                          {:select [:foo] :from [:bar2]}]})
         ["SELECT foo FROM bar1 UNION SELECT foo FROM bar2"]))

  (testing "union complex values"
    (is (= (format {:union [{:select [:foo] :from [:bar1]}
                            {:select [:foo] :from [:bar2]}]
                    :with [[[:bar {:columns [:spam :eggs]}]
                            {:values [[1 2] [3 4] [5 6]]}]]})
           ["WITH bar (spam, eggs) AS (VALUES (?, ?), (?, ?), (?, ?)) SELECT foo FROM bar1 UNION SELECT foo FROM bar2"
            1 2 3 4 5 6]))))

(deftest union-all-test
  (is (= (format {:union-all [{:select [:foo] :from [:bar1]}
                              {:select [:foo] :from [:bar2]}]})
         ["SELECT foo FROM bar1 UNION ALL SELECT foo FROM bar2"])))

(deftest intersect-test
  (is (= (format {:intersect [{:select [:foo] :from [:bar1]}
                              {:select [:foo] :from [:bar2]}]})
         ["SELECT foo FROM bar1 INTERSECT SELECT foo FROM bar2"])))

(deftest except-test
  (is (= (format {:except [{:select [:foo] :from [:bar1]}
                           {:select [:foo] :from [:bar2]}]})
         ["SELECT foo FROM bar1 EXCEPT SELECT foo FROM bar2"])))

(deftest inner-parts-test
  (testing "The correct way to apply ORDER BY to various parts of a UNION"
    (is (= (format
             {:union
              [{:select [:amount :id :created_on]
                :from [:transactions]}
               {:select [:amount :id :created_on]
                :from [{:select [:amount :id :created_on]
                        :from [:other_transactions]
                        :order-by [[:amount :desc]]
                        :limit 5}]}]
              :order-by [[:amount :asc]]})
           ["SELECT amount, id, created_on FROM transactions UNION SELECT amount, id, created_on FROM (SELECT amount, id, created_on FROM other_transactions ORDER BY amount DESC LIMIT ?) ORDER BY amount ASC" 5]))))

(deftest compare-expressions-test
  (testing "Sequences should be fns when in value/comparison spots"
    (is (= ["SELECT foo FROM bar WHERE (col1 MOD ?) = (col2 + ?)" 4 4]
           (format {:select [:foo]
                    :from [:bar]
                    :where [:= [:mod :col1 4] [:+ :col2 4]]}))))

  (testing "Value context only applies to sequences in value/comparison spots"
    (let [sub {:select [:%sum.amount]
               :from [:bar]
               :where [:in :id ["id-1" "id-2"]]}]
      (is (= ["SELECT total FROM foo WHERE (SELECT sum(amount) FROM bar WHERE id IN (?, ?)) = total" "id-1" "id-2"]
             (format {:select [:total]
                      :from [:foo]
                      :where [:= sub :total]})))
      (is (= ["WITH t AS (SELECT sum(amount) FROM bar WHERE id IN (?, ?)) SELECT total FROM foo WHERE total = t" "id-1" "id-2"]
             (format {:with [[:t sub]]
                      :select [:total]
                      :from [:foo]
                      :where [:= :total :t]}))))))

(deftest union-with-cte
  (is (= (format {:union [{:select [:foo] :from [:bar1]}
                          {:select [:foo] :from [:bar2]}]
                  :with [[[:bar {:columns [:spam :eggs]}]
                          {:values [[1 2] [3 4] [5 6]]}]]})
         ["WITH bar (spam, eggs) AS (VALUES (?, ?), (?, ?), (?, ?)) SELECT foo FROM bar1 UNION SELECT foo FROM bar2" 1 2 3 4 5 6])))


(deftest union-all-with-cte
  (is (= (format {:union-all [{:select [:foo] :from [:bar1]}
                              {:select [:foo] :from [:bar2]}]
                  :with [[[:bar {:columns [:spam :eggs]}]
                          {:values [[1 2] [3 4] [5 6]]}]]})
         ["WITH bar (spam, eggs) AS (VALUES (?, ?), (?, ?), (?, ?)) SELECT foo FROM bar1 UNION ALL SELECT foo FROM bar2" 1 2 3 4 5 6])))

(deftest parameterizer-none
  (testing "array parameter"
    (is (= (format {:insert-into :foo
                    :columns [:baz]
                    :values [[[:array [1 2 3 4]]]]}
                   {:parameterizer :none})
           ["INSERT INTO foo (baz) VALUES (ARRAY[1, 2, 3, 4])"])))

  (testing "union complex values"
    (is (= (format {:union [{:select [:foo] :from [:bar1]}
                            {:select [:foo] :from [:bar2]}]
                    :with [[[:bar {:columns [:spam :eggs]}]
                            {:values [[1 2] [3 4] [5 6]]}]]}
                   {:parameterizer :none})
           ["WITH bar (spam, eggs) AS (VALUES (1, 2), (3, 4), (5, 6)) SELECT foo FROM bar1 UNION SELECT foo FROM bar2"]))))

(deftest where-and
  (testing "should ignore a nil predicate"
    (is (= (format {:where [:and [:= :foo "foo"] [:= :bar "bar"] nil]}
                   {:parameterizer :postgresql})
           ["WHERE (foo = $1 AND bar = $2)" "foo" "bar"]))))


#_(defmethod parameterize :single-quote [_ value pname] (str \' value \'))
#_(defmethod parameterize :mysql-fill [_ value pname] "?")

(deftest customized-parameterizer
  (testing "should fill param with single quote"
    (is (= (format {:where [:and [:= :foo "foo"] [:= :bar "bar"] nil]}
                   {:parameterizer :single-quote})
           ["WHERE (foo = 'foo' AND bar = 'bar')" "foo" "bar"])))
  (testing "should fill param with ?"
    (is (= (format {:where [:and [:= :foo "foo"] [:= :bar "bar"] nil]}
                   {:parameterizer :mysql-fill})
           ["WHERE (foo = ? AND bar = ?)" "foo" "bar"]))))


(deftest set-before-from ; issue 235
  (is (=
       ["UPDATE \"films\" \"f\" SET \"kind\" = \"c\".\"test\" FROM (SELECT \"b\".\"test\" FROM \"bar\" \"b\" WHERE \"b\".\"id\" = ?) \"c\" WHERE \"f\".\"kind\" = ?" 1 "drama"]
       (->
         {:update [:films :f]
          :set    {:kind :c.test}
          :from   [[{:select [:b.test]
                     :from   [[:bar :b]]
                     :where  [:= :b.id 1]} :c]]
          :where  [:= :f.kind "drama"]}
         (format {:quoted true})))))

(deftest set-after-join
  (is (=
       ["UPDATE `foo` INNER JOIN `bar` ON `bar`.`id` = `foo`.`bar_id` SET `a` = ? WHERE `bar`.`b` = ?" 1 42]
       (->
         {:update :foo
          :join   [:bar [:= :bar.id :foo.bar_id]]
          :set    {:a 1}
          :where  [:= :bar.b 42]}
         (format {:dialect :mysql})))))

(deftest delete-from-test
  (is (= ["DELETE FROM `foo` WHERE `foo`.`id` = ?" 42]
         (-> {:delete-from :foo
              :where [:= :foo.id 42]}
             (format {:dialect :mysql})))))

(deftest delete-test
  (is (= ["DELETE `t1`, `t2` FROM `table1` `t1` INNER JOIN `table2` `t2` ON `t1`.`fk` = `t2`.`id` WHERE `t1`.`bar` = ?" 42]
         (-> {:delete [:t1 :t2]
              :from [[:table1 :t1]]
              :join [[:table2 :t2] [:= :t1.fk :t2.id]]
              :where [:= :t1.bar 42]}
             (format {:dialect :mysql})))))

(deftest truncate-test
  (is (= ["TRUNCATE `foo`"]
         (-> {:truncate :foo}
             (format {:dialect :mysql})))))

(deftest inlined-values-are-stringified-correctly
  (is (= ["SELECT 'foo', 'It''s a quote!', bar, NULL"]
         (format {:select [[[:inline "foo"]]
                           [[:inline "It's a quote!"]]
                           [[:inline :bar]]
                           [[:inline nil]]]}))))

;; Make sure if Locale is Turkish we're not generating queries like İNNER JOIN (dot over the I) because
;; `string/upper-case` is converting things to upper-case using the default Locale. Generated query should be the same
;; regardless of system Locale. See #236
#?(:clj
   (deftest statements-generated-correctly-with-turkish-locale
     (let [format-with-locale (fn [^String language-tag]
                                (let [original-locale (java.util.Locale/getDefault)]
                                  (try
                                    (java.util.Locale/setDefault (java.util.Locale/forLanguageTag language-tag))
                                    (format {:select [:t2.name]
                                             :from   [[:table1 :t1]]
                                             :join   [[:table2 :t2] [:= :t1.fk :t2.id]]
                                             :where  [:= :t1.id 1]})
                                    (finally
                                      (java.util.Locale/setDefault original-locale)))))]
       (is (= (format-with-locale "en")
              (format-with-locale "tr"))))))

(deftest join-on-true-253
  ;; used to work on honeysql 0.9.2; broke in 0.9.3
  (is (= ["SELECT foo FROM bar INNER JOIN table t ON TRUE"]
         (format {:select [:foo]
                  :from [:bar]
                  :join [[:table :t] true]}))))

(deftest cross-join-test
  (is (= ["SELECT * FROM foo CROSS JOIN bar"]
         (format {:select [:*]
                  :from [:foo]
                  :cross-join [:bar]})))
  (is (= ["SELECT * FROM foo f CROSS JOIN bar b"]
         (format {:select [:*]
                  :from [[:foo :f]]
                  :cross-join [[:bar :b]]}))))
