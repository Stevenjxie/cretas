-- SH-01 phase A: remove runtime routing to the inventory-blind legacy shipment write tools.
-- The legacy GET endpoints and shipment_records table remain temporarily for installed-client reads.

DO $sh01_ai$
BEGIN
    IF EXISTS (
        SELECT 1
          FROM public.ai_intent_configs
         WHERE tool_name IN (
             'shipment_create',
             'shipment_update',
             'shipment_status_update',
             'shipment_confirm',
             'shipment_complete',
             'shipment_cancel',
             'shipment_delete',
             'shipment_notify_warehouse'
         )
           AND intent_code NOT IN (
             'SHIPMENT_CREATE',
             'SHIPMENT_UPDATE',
             'SHIPMENT_STATUS_UPDATE',
             'SHIPMENT_DELETE'
         )
    ) THEN
        RAISE EXCEPTION 'SH-01 blocked: unexpected intent is bound to a legacy shipment mutation tool';
    END IF;
END
$sh01_ai$;

-- Creation moves to the inventory-aware sales delivery model. The remaining legacy mutations
-- have no semantically safe automatic translation and are disabled instead of falling back.
UPDATE public.ai_intent_configs
   SET tool_name = 'sales_create_delivery',
       updated_at = NOW()
 WHERE intent_code = 'SHIPMENT_CREATE'
   AND tool_name = 'shipment_create';

UPDATE public.ai_intent_configs
   SET is_active = FALSE,
       updated_at = NOW()
 WHERE tool_name IN (
     'shipment_update',
     'shipment_status_update',
     'shipment_confirm',
     'shipment_complete',
     'shipment_cancel',
     'shipment_delete',
     'shipment_notify_warehouse'
 );

-- Embeddings must not keep suggesting tool names that no longer have executors.
DELETE FROM public.tool_embeddings
 WHERE tool_name IN (
     'shipment_create',
     'shipment_update',
     'shipment_status_update',
     'shipment_confirm',
     'shipment_complete',
     'shipment_cancel',
     'shipment_delete',
     'shipment_notify_warehouse'
 );
