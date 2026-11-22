-- Only fixed capability functions may cross the global identity write boundary.
CREATE TABLE platform.tenant_invitations (
 id uuid PRIMARY KEY,
 tenant_id uuid NOT NULL REFERENCES platform.tenants(id),
 invited_email_normalized varchar(254) NOT NULL,
 invited_role varchar(6) NOT NULL CHECK(invited_role IN ('ADMIN','MEMBER')),
 invited_by uuid NOT NULL REFERENCES platform.users(id),
 token_hash bytea NOT NULL UNIQUE CHECK(octet_length(token_hash)=32),
 status varchar(9) NOT NULL DEFAULT 'PENDING' CHECK(status IN ('PENDING','ACCEPTED','CANCELLED')),
 created_at timestamptz NOT NULL,
 expires_at timestamptz NOT NULL CHECK(expires_at=created_at+interval '48 hours'),
 accepted_at timestamptz,
 accepted_by uuid REFERENCES platform.users(id),
 cancelled_at timestamptz,
 cancelled_by uuid REFERENCES platform.users(id),
 CHECK(length(split_part(invited_email_normalized,'@',1))<=64),
 CHECK(invited_email_normalized=lower(btrim(invited_email_normalized)) AND invited_email_normalized ~ '^[a-z0-9!#$%&''*+/=?^_`{|}~-]+(\.[a-z0-9!#$%&''*+/=?^_`{|}~-]+)*@[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?(\.[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?)*\.[a-z]{2,63}$'),
 CHECK((status='PENDING' AND accepted_at IS NULL AND accepted_by IS NULL AND cancelled_at IS NULL AND cancelled_by IS NULL)
 OR (status='ACCEPTED' AND accepted_at IS NOT NULL AND accepted_by IS NOT NULL AND accepted_at>=created_at AND accepted_at<expires_at AND cancelled_at IS NULL AND cancelled_by IS NULL)
 OR (status='CANCELLED' AND cancelled_at IS NOT NULL AND cancelled_by IS NOT NULL AND cancelled_at>=created_at AND accepted_at IS NULL AND accepted_by IS NULL))
);
CREATE INDEX invitations_tenant_created_idx ON platform.tenant_invitations(tenant_id,created_at DESC,id DESC);
CREATE INDEX invitations_live_idx ON platform.tenant_invitations(tenant_id,invited_email_normalized,expires_at) WHERE status='PENDING';
CREATE TABLE platform.tenant_access_audit (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 tenant_id uuid NOT NULL REFERENCES platform.tenants(id),
 actor_id uuid REFERENCES platform.users(id),
 target_user_id uuid REFERENCES platform.users(id),
 invitation_id uuid REFERENCES platform.tenant_invitations(id),
 action varchar(24) NOT NULL CHECK(action IN ('INVITATION_CREATED','INVITATION_CANCELLED','INVITATION_ACCEPTED','MEMBERSHIP_ROLE_CHANGED','MEMBERSHIP_REVOKED')),
 previous_role varchar(6) CHECK(previous_role IN ('ADMIN','MEMBER')),
 new_role varchar(6) CHECK(new_role IN ('ADMIN','MEMBER')),
 occurred_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 correlation_id uuid NOT NULL,
 CHECK((action='INVITATION_CREATED' AND actor_id IS NOT NULL AND invitation_id IS NOT NULL AND target_user_id IS NULL AND previous_role IS NULL AND new_role IS NOT NULL)
 OR (action='INVITATION_CANCELLED' AND actor_id IS NOT NULL AND invitation_id IS NOT NULL AND target_user_id IS NULL AND previous_role IS NULL AND new_role IS NULL)
 OR (action='INVITATION_ACCEPTED' AND target_user_id IS NOT NULL AND invitation_id IS NOT NULL AND previous_role IS NULL AND new_role IS NOT NULL)
 OR (action='MEMBERSHIP_ROLE_CHANGED' AND actor_id IS NOT NULL AND target_user_id IS NOT NULL AND invitation_id IS NULL AND previous_role IS NOT NULL AND new_role IS NOT NULL AND previous_role<>new_role)
 OR (action='MEMBERSHIP_REVOKED' AND actor_id IS NOT NULL AND target_user_id IS NOT NULL AND invitation_id IS NULL AND previous_role IS NOT NULL AND new_role IS NULL))
);
CREATE INDEX access_audit_revocation_idx ON platform.tenant_access_audit(tenant_id,target_user_id,occurred_at) WHERE action='MEMBERSHIP_REVOKED';
CREATE INDEX access_audit_tenant_created_idx ON platform.tenant_access_audit(tenant_id,occurred_at DESC,id DESC);
ALTER TABLE platform.tenant_invitations ENABLE ROW LEVEL SECURITY;
ALTER TABLE platform.tenant_invitations FORCE ROW LEVEL SECURITY;
ALTER TABLE platform.tenant_access_audit ENABLE ROW LEVEL SECURITY;
ALTER TABLE platform.tenant_access_audit FORCE ROW LEVEL SECURITY;
-- Privileged read is required only to locate a capability by its high-entropy digest.
-- Runtime has NO table grants, including no hash/audit access. All callable operations reauthorize.
CREATE POLICY invitation_capability_lookup ON platform.tenant_invitations FOR SELECT TO eaap_migration USING(true);
CREATE POLICY invitation_capability_insert ON platform.tenant_invitations FOR INSERT TO eaap_migration WITH CHECK(tenant_id=platform.current_tenant_id());
CREATE POLICY invitation_capability_update ON platform.tenant_invitations FOR UPDATE TO eaap_migration USING(tenant_id=platform.current_tenant_id() AND status='PENDING') WITH CHECK(tenant_id=platform.current_tenant_id());
CREATE POLICY access_audit_insert ON platform.tenant_access_audit FOR INSERT TO eaap_migration WITH CHECK(tenant_id=platform.current_tenant_id());
CREATE POLICY access_audit_read ON platform.tenant_access_audit FOR SELECT TO eaap_migration USING(tenant_id=platform.current_tenant_id());

-- Helpers are private to the migration-owned capability functions, never callable by runtime.
CREATE FUNCTION platform.access_tenant_lock(t uuid) RETURNS void LANGUAGE sql SET search_path='' AS $$
 SELECT pg_catalog.pg_advisory_xact_lock(pg_catalog.hashtextextended('eaap-access:'||t::text,0))
$$;
CREATE FUNCTION platform.access_actor(t uuid,a uuid) RETURNS text LANGUAGE plpgsql SET search_path='' AS $$
DECLARE r text;
BEGIN
 IF t IS NULL OR t IS DISTINCT FROM platform.current_tenant_id() OR a IS NULL THEN RAISE EXCEPTION 'access_forbidden' USING ERRCODE='P0001'; END IF;
 SELECT m.role INTO r FROM platform.tenant_memberships m JOIN platform.users u ON u.id=m.user_id
 WHERE m.tenant_id=t AND m.user_id=a AND u.enabled FOR SHARE OF m,u;
 IF r IS NULL THEN RAISE EXCEPTION 'access_forbidden' USING ERRCODE='P0001'; END IF;
 RETURN r;
END $$;
CREATE FUNCTION platform.access_page(n integer,o bigint) RETURNS void LANGUAGE plpgsql SET search_path='' AS $$
BEGIN IF n IS NULL OR n<1 OR n>101 OR o IS NULL OR o<0 OR o>10000 THEN RAISE EXCEPTION 'access_invalid' USING ERRCODE='P0001'; END IF; END $$;
REVOKE ALL ON FUNCTION platform.access_tenant_lock(uuid),platform.access_actor(uuid,uuid),platform.access_page(integer,bigint) FROM PUBLIC;

CREATE FUNCTION platform.access_members(t uuid,a uuid,n integer,o bigint)
RETURNS TABLE(user_id uuid,email text,role text,created_at timestamptz) LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE r text;
BEGIN
 r:=platform.access_actor(t,a);PERFORM platform.access_page(n,o);
 RETURN QUERY SELECT m.user_id,CASE WHEN r IN ('OWNER','ADMIN') OR m.user_id=a THEN u.email::text ELSE NULL::text END,m.role::text,m.created_at
 FROM platform.tenant_memberships m JOIN platform.users u ON u.id=m.user_id WHERE m.tenant_id=t ORDER BY m.created_at DESC,m.user_id DESC LIMIT n OFFSET o;
END $$;
CREATE FUNCTION platform.access_invitations(t uuid,a uuid,n integer,o bigint)
RETURNS TABLE(id uuid,tenant_id uuid,email text,role text,invited_by uuid,status text,created_at timestamptz,expires_at timestamptz,expired boolean,accepted_at timestamptz,accepted_by uuid,cancelled_at timestamptz,cancelled_by uuid)
LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
BEGIN
 IF platform.access_actor(t,a) NOT IN ('OWNER','ADMIN') THEN RAISE EXCEPTION 'access_forbidden' USING ERRCODE='P0001'; END IF;
 PERFORM platform.access_page(n,o);
 RETURN QUERY SELECT i.id,i.tenant_id,i.invited_email_normalized::text,i.invited_role::text,i.invited_by,i.status::text,i.created_at,i.expires_at,(i.status='PENDING' AND i.expires_at<=clock_timestamp()),i.accepted_at,i.accepted_by,i.cancelled_at,i.cancelled_by
 FROM platform.tenant_invitations i WHERE i.tenant_id=t ORDER BY i.created_at DESC,i.id DESC LIMIT n OFFSET o;
END $$;
CREATE FUNCTION platform.access_invite(t uuid,a uuid,i uuid,e text,r text,h bytea,c uuid) RETURNS uuid
LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE actor_role text; now_at timestamptz;
BEGIN
 IF t IS DISTINCT FROM platform.current_tenant_id() THEN RAISE EXCEPTION 'access_forbidden' USING ERRCODE='P0001'; END IF;
 PERFORM platform.access_tenant_lock(t);actor_role:=platform.access_actor(t,a);
 IF actor_role NOT IN ('OWNER','ADMIN') OR (actor_role='ADMIN' AND r IS DISTINCT FROM 'MEMBER') THEN RAISE EXCEPTION 'access_forbidden' USING ERRCODE='P0001'; END IF;
 IF r IS NULL OR r NOT IN ('ADMIN','MEMBER') OR i IS NULL OR c IS NULL OR h IS NULL OR octet_length(h)<>32 THEN RAISE EXCEPTION 'access_invalid' USING ERRCODE='P0001'; END IF;
 now_at:=clock_timestamp();
 IF (SELECT count(*) FROM platform.tenant_invitations x WHERE x.tenant_id=t AND x.status='PENDING' AND x.expires_at>now_at)>=5 THEN RAISE EXCEPTION 'invitation_limit' USING ERRCODE='P0001'; END IF;
 IF EXISTS(SELECT 1 FROM platform.tenant_invitations x WHERE x.tenant_id=t AND x.invited_email_normalized=e AND x.status='PENDING' AND x.expires_at>now_at) THEN RAISE EXCEPTION 'invitation_conflict' USING ERRCODE='P0001'; END IF;
 INSERT INTO platform.tenant_invitations(id,tenant_id,invited_email_normalized,invited_role,invited_by,token_hash,created_at,expires_at) VALUES(i,t,e,r,a,h,now_at,now_at+interval '48 hours');
 INSERT INTO platform.tenant_access_audit(tenant_id,actor_id,invitation_id,action,new_role,correlation_id) VALUES(t,a,i,'INVITATION_CREATED',r,c);
 RETURN i;
END $$;
CREATE FUNCTION platform.access_cancel(t uuid,a uuid,i uuid,c uuid) RETURNS void
LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE actor_role text; invitation platform.tenant_invitations%ROWTYPE;
BEGIN
 IF t IS DISTINCT FROM platform.current_tenant_id() THEN RAISE EXCEPTION 'access_forbidden' USING ERRCODE='P0001'; END IF;
 PERFORM platform.access_tenant_lock(t);actor_role:=platform.access_actor(t,a);
 IF actor_role NOT IN ('OWNER','ADMIN') THEN RAISE EXCEPTION 'access_forbidden' USING ERRCODE='P0001'; END IF;
 SELECT * INTO invitation FROM platform.tenant_invitations x WHERE x.tenant_id=t AND x.id=i;
 IF invitation.id IS NULL THEN RAISE EXCEPTION 'access_not_found' USING ERRCODE='P0001'; END IF;
 IF actor_role='ADMIN' AND (invitation.invited_role<>'MEMBER' OR invitation.invited_by<>a) THEN RAISE EXCEPTION 'access_forbidden' USING ERRCODE='P0001'; END IF;
 IF invitation.status<>'PENDING' THEN RAISE EXCEPTION 'invitation_conflict' USING ERRCODE='P0001'; END IF;
 UPDATE platform.tenant_invitations SET status='CANCELLED',cancelled_at=clock_timestamp(),cancelled_by=a WHERE id=i AND tenant_id=t AND status='PENDING';
 INSERT INTO platform.tenant_access_audit(tenant_id,actor_id,invitation_id,action,correlation_id) VALUES(t,a,i,'INVITATION_CANCELLED',c);
END $$;
CREATE FUNCTION platform.access_member_change(t uuid,a uuid,u uuid,r text,remove_member boolean,c uuid) RETURNS void
LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE actor_role text; old_role text;
BEGIN
 IF t IS DISTINCT FROM platform.current_tenant_id() THEN RAISE EXCEPTION 'access_forbidden' USING ERRCODE='P0001'; END IF;
 PERFORM platform.access_tenant_lock(t);actor_role:=platform.access_actor(t,a);
 IF actor_role NOT IN ('OWNER','ADMIN') OR u=a OR remove_member IS NULL THEN RAISE EXCEPTION 'access_forbidden' USING ERRCODE='P0001'; END IF;
 SELECT m.role INTO old_role FROM platform.tenant_memberships m WHERE m.tenant_id=t AND m.user_id=u FOR UPDATE;
 IF old_role IS NULL THEN RAISE EXCEPTION 'access_not_found' USING ERRCODE='P0001'; END IF;
 IF old_role='OWNER' OR (actor_role='ADMIN' AND (NOT remove_member OR old_role<>'MEMBER')) THEN RAISE EXCEPTION 'access_forbidden' USING ERRCODE='P0001'; END IF;
 IF remove_member THEN
  IF r IS NOT NULL THEN RAISE EXCEPTION 'access_invalid' USING ERRCODE='P0001'; END IF;
  DELETE FROM platform.tenant_memberships m WHERE m.tenant_id=t AND m.user_id=u;
  INSERT INTO platform.tenant_access_audit(tenant_id,actor_id,target_user_id,action,previous_role,correlation_id) VALUES(t,a,u,'MEMBERSHIP_REVOKED',old_role,c);
 ELSE
  IF r IS NULL OR r NOT IN ('ADMIN','MEMBER') THEN RAISE EXCEPTION 'access_invalid' USING ERRCODE='P0001'; END IF;
  IF r=old_role THEN RETURN; END IF;
  UPDATE platform.tenant_memberships m SET role=r WHERE m.tenant_id=t AND m.user_id=u;
  INSERT INTO platform.tenant_access_audit(tenant_id,actor_id,target_user_id,action,previous_role,new_role,correlation_id) VALUES(t,a,u,'MEMBERSHIP_ROLE_CHANGED',old_role,r,c);
 END IF;
END $$;

-- Private lock-and-validate capability; no public/runtime execute grant.
CREATE FUNCTION platform.access_redeem(h bytea) RETURNS platform.tenant_invitations
LANGUAGE plpgsql SET search_path='' AS $$
DECLARE invitation platform.tenant_invitations%ROWTYPE; t uuid; issuer_role text;
BEGIN
 IF h IS NULL OR octet_length(h)<>32 THEN RAISE EXCEPTION 'invitation_unavailable' USING ERRCODE='P0001'; END IF;
 SELECT x.tenant_id INTO t FROM platform.tenant_invitations x WHERE x.token_hash=h;
 IF t IS NULL THEN RAISE EXCEPTION 'invitation_unavailable' USING ERRCODE='P0001'; END IF;
 PERFORM platform.access_tenant_lock(t);
 SELECT * INTO invitation FROM platform.tenant_invitations x WHERE x.token_hash=h;
 IF invitation.id IS NULL OR invitation.status<>'PENDING' OR invitation.expires_at<=clock_timestamp() THEN RAISE EXCEPTION 'invitation_unavailable' USING ERRCODE='P0001'; END IF;
 PERFORM pg_catalog.set_config('app.tenant_id',t::text,true);
 -- Revoked/demoted inviters cannot leave a capability granting greater current authority.
 issuer_role:=platform.membership_role(invitation.invited_by,t);
 IF issuer_role IS NULL OR issuer_role NOT IN ('OWNER','ADMIN') OR (issuer_role='ADMIN' AND invitation.invited_role<>'MEMBER') THEN RAISE EXCEPTION 'invitation_unavailable' USING ERRCODE='P0001'; END IF;
 IF EXISTS(SELECT 1 FROM platform.tenant_access_audit ev JOIN platform.users u ON u.id=ev.target_user_id WHERE ev.tenant_id=t AND u.email=invitation.invited_email_normalized AND ev.action='MEMBERSHIP_REVOKED' AND ev.occurred_at>=invitation.created_at) THEN RAISE EXCEPTION 'invitation_unavailable' USING ERRCODE='P0001'; END IF;
 RETURN invitation;
END $$;
CREATE FUNCTION platform.access_accept_new(h bytea,e text,p text,u uuid,c uuid)
RETURNS TABLE(invitation_id uuid,tenant_id uuid,user_id uuid,role text) LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE invitation platform.tenant_invitations%ROWTYPE; new_id uuid; accepted_time timestamptz;
BEGIN
 invitation:=platform.access_redeem(h);
 IF e IS DISTINCT FROM invitation.invited_email_normalized OR u IS NULL OR c IS NULL OR p IS NULL
 OR p !~ '^\$argon2id\$v=19\$m=19456,t=2,p=1\$[A-Za-z0-9+/]{22}\$[A-Za-z0-9+/]{43}$' THEN RAISE EXCEPTION 'invitation_unavailable' USING ERRCODE='P0001'; END IF;
 INSERT INTO platform.users(id,email,password_hash) VALUES(u,invitation.invited_email_normalized,p) ON CONFLICT(email) DO NOTHING RETURNING id INTO new_id;
 IF new_id IS NULL THEN RAISE EXCEPTION 'invitation_unavailable' USING ERRCODE='P0001'; END IF;
 INSERT INTO platform.tenant_memberships(user_id,tenant_id,role) VALUES(new_id,invitation.tenant_id,invitation.invited_role);
 accepted_time:=clock_timestamp();
 IF accepted_time>=invitation.expires_at THEN RAISE EXCEPTION 'invitation_unavailable' USING ERRCODE='P0001'; END IF;
 UPDATE platform.tenant_invitations SET status='ACCEPTED',accepted_at=accepted_time,accepted_by=new_id WHERE id=invitation.id;
 INSERT INTO platform.tenant_access_audit(tenant_id,target_user_id,invitation_id,action,new_role,correlation_id) VALUES(invitation.tenant_id,new_id,invitation.id,'INVITATION_ACCEPTED',invitation.invited_role,c);
 RETURN QUERY SELECT invitation.id,invitation.tenant_id,new_id,invitation.invited_role::text;
END $$;
CREATE FUNCTION platform.access_accept_existing(h bytea,a uuid,c uuid)
RETURNS TABLE(invitation_id uuid,tenant_id uuid,user_id uuid,role text) LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE invitation platform.tenant_invitations%ROWTYPE; user_email text; accepted_time timestamptz;
BEGIN
 invitation:=platform.access_redeem(h);
 SELECT x.email INTO user_email FROM platform.users x WHERE x.id=a AND x.enabled FOR SHARE;
 IF user_email IS NULL OR user_email<>invitation.invited_email_normalized OR c IS NULL THEN RAISE EXCEPTION 'invitation_unavailable' USING ERRCODE='P0001'; END IF;
 IF EXISTS(SELECT 1 FROM platform.tenant_memberships m WHERE m.user_id=a AND m.tenant_id=invitation.tenant_id) THEN RAISE EXCEPTION 'membership_conflict' USING ERRCODE='P0001'; END IF;
 INSERT INTO platform.tenant_memberships(user_id,tenant_id,role) VALUES(a,invitation.tenant_id,invitation.invited_role);
 accepted_time:=clock_timestamp();
 IF accepted_time>=invitation.expires_at THEN RAISE EXCEPTION 'invitation_unavailable' USING ERRCODE='P0001'; END IF;
 UPDATE platform.tenant_invitations SET status='ACCEPTED',accepted_at=accepted_time,accepted_by=a WHERE id=invitation.id;
 INSERT INTO platform.tenant_access_audit(tenant_id,actor_id,target_user_id,invitation_id,action,new_role,correlation_id) VALUES(invitation.tenant_id,a,a,invitation.id,'INVITATION_ACCEPTED',invitation.invited_role,c);
 RETURN QUERY SELECT invitation.id,invitation.tenant_id,a,invitation.invited_role::text;
END $$;
REVOKE ALL ON FUNCTION platform.access_redeem(bytea) FROM PUBLIC;
REVOKE ALL ON FUNCTION platform.access_members(uuid,uuid,integer,bigint),platform.access_invitations(uuid,uuid,integer,bigint),platform.access_invite(uuid,uuid,uuid,text,text,bytea,uuid),platform.access_cancel(uuid,uuid,uuid,uuid),platform.access_member_change(uuid,uuid,uuid,text,boolean,uuid),platform.access_accept_new(bytea,text,text,uuid,uuid),platform.access_accept_existing(bytea,uuid,uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION platform.access_members(uuid,uuid,integer,bigint),platform.access_invitations(uuid,uuid,integer,bigint),platform.access_invite(uuid,uuid,uuid,text,text,bytea,uuid),platform.access_cancel(uuid,uuid,uuid,uuid),platform.access_member_change(uuid,uuid,uuid,text,boolean,uuid),platform.access_accept_new(bytea,text,text,uuid,uuid),platform.access_accept_existing(bytea,uuid,uuid) TO eaap_runtime;

CREATE FUNCTION platform.access_invitation(t uuid,a uuid,i uuid)
RETURNS TABLE(id uuid,tenant_id uuid,email text,role text,invited_by uuid,status text,created_at timestamptz,expires_at timestamptz,expired boolean,accepted_at timestamptz,accepted_by uuid,cancelled_at timestamptz,cancelled_by uuid)
LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
BEGIN
 IF platform.access_actor(t,a) NOT IN ('OWNER','ADMIN') THEN RAISE EXCEPTION 'access_forbidden' USING ERRCODE='P0001'; END IF;
 RETURN QUERY SELECT x.id,x.tenant_id,x.invited_email_normalized::text,x.invited_role::text,x.invited_by,x.status::text,x.created_at,x.expires_at,(x.status='PENDING' AND x.expires_at<=clock_timestamp()),x.accepted_at,x.accepted_by,x.cancelled_at,x.cancelled_by
 FROM platform.tenant_invitations x WHERE x.tenant_id=t AND x.id=i;
END $$;
REVOKE ALL ON FUNCTION platform.access_invitation(uuid,uuid,uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION platform.access_invitation(uuid,uuid,uuid) TO eaap_runtime;
