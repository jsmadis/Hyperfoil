package io.hyperfoil.api.session;

import java.io.Serializable;

public interface Access extends Serializable {
   void declareObject(Session session);

   void declareInt(Session session);

   boolean isSet(Session session);

   Object getObject(Session session);

   void setObject(Session session, Object value);

   int getInt(Session session);

   void setInt(Session session, int value);

   Session.Var getVar(Session session);

   int addToInt(Session session, int delta);

   /**
    * Make variable set without changing its (pre-allocated) value.
    *
    * @param session Session with variables.
    * @return Variable value
    */
   Object activate(Session session);

   void unset(Session session);

   boolean isSequenceScoped();
}
